/*
 * Copyright 2000-2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.server.rest.data.finder.impl;

import com.intellij.openapi.diagnostic.Logger;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.messages.ErrorData;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.parameters.impl.AbstractMapParametersProvider;
import jetbrains.buildServer.server.rest.data.*;
import jetbrains.buildServer.server.rest.data.build.TagFinder;
import jetbrains.buildServer.server.rest.data.finder.*;
import jetbrains.buildServer.server.rest.data.problem.TestFinder;
import jetbrains.buildServer.server.rest.data.problem.TestOccurrenceFinder;
import jetbrains.buildServer.server.rest.data.util.*;
import jetbrains.buildServer.server.rest.data.util.itemholder.ItemHolder;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.errors.LocatorProcessException;
import jetbrains.buildServer.server.rest.errors.NotFoundException;
import jetbrains.buildServer.server.rest.jersey.provider.annotated.JerseyContextSingleton;
import jetbrains.buildServer.server.rest.model.ItemsProviders;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.model.Util;
import jetbrains.buildServer.server.rest.model.agent.Agent;
import jetbrains.buildServer.server.rest.model.build.Build;
import jetbrains.buildServer.server.rest.request.Constants;
import jetbrains.buildServer.server.rest.swagger.annotations.LocatorDimension;
import jetbrains.buildServer.server.rest.swagger.annotations.LocatorResource;
import jetbrains.buildServer.server.rest.swagger.constants.CommonLocatorDimensionsList;
import jetbrains.buildServer.server.rest.swagger.constants.LocatorDimensionDataType;
import jetbrains.buildServer.server.rest.swagger.constants.LocatorName;
import jetbrains.buildServer.server.rest.util.StreamUtil;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.auth.AccessDeniedException;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.auth.SecurityContext;
import jetbrains.buildServer.serverSide.dependency.BuildDependency;
import jetbrains.buildServer.serverSide.metadata.BuildMetadataEntry;
import jetbrains.buildServer.serverSide.metadata.impl.MetadataStorageEx;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.StandardProperties;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.Converter;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.filters.Filter;
import jetbrains.buildServer.vcs.SVcsRoot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * @author Yegor.Yarko
 *         Date: 20.08.2014
 */
@LocatorResource(value = LocatorName.BUILD,
    extraDimensions = {FinderImpl.DIMENSION_ID, PagerData.START, PagerData.COUNT, FinderImpl.DIMENSION_LOOKUP_LIMIT, CommonLocatorDimensionsList.PROPERTY, AbstractFinder.DIMENSION_ITEM},
    baseEntity = "Build",
    examples = {
        "`id:1` — find build with ID `1`.",
        "`buildType:<buildTypeLocator>` — find builds under build configuration found by `buildTypeLocator`.",
        "`defaultFilter:false,agent:<agentLocator>` — find builds executed on agent found by `agentLocator`, with `defaultFilter` disabled (see below)."
    }
)
@JerseyContextSingleton
@Component("restBuildPromotionFinder") // Name copied from context xml file.
public class BuildPromotionFinder extends AbstractFinder<BuildPromotion> {
  private static final Logger LOG = Logger.getInstance(BuildPromotionFinder.class.getName());

  //DIMENSION_ID - id of a build or id of build promotion which will get associated build with the id
  @LocatorDimension(value = "taskId", dataType = LocatorDimensionDataType.INTEGER, notes = "ID of a build or build promotion.")
  public static final String PROMOTION_ID = "taskId";
  @LocatorDimension(value = "prmotionId", dataType = LocatorDimensionDataType.INTEGER, notes = "ID of a build or build promotion (compatibility with TC 8.0).", hidden = true)
  protected static final String PROMOTION_ID_ALIAS = "promotionId";
  @LocatorDimension(value = "buildId", format = LocatorDimensionDataType.INTEGER, notes = "Build type locator (experimental, for debug purposes only).", hidden = true)
  protected static final String BUILD_ID = "buildId";
  @LocatorDimension(value = "buildType", format = LocatorName.BUILD_TYPE, notes = "Build type locator.")
  public static final String BUILD_TYPE = "buildType";
  @LocatorDimension(value = "project", format = LocatorName.PROJECT, notes = "Project (direct parent) locator.")
  public static final String PROJECT = "project"; // BuildFinder (used prior to 9.0) treats "project" as "affectedProject" and thus this behavior is different from BuildFinder
  @LocatorDimension(value = "affectedProject", format = LocatorName.PROJECT, notes = "Project (direct or indirect parent) locator.")
  private static final String AFFECTED_PROJECT = "affectedProject";
  @LocatorDimension(value = "agent", format = LocatorName.AGENT, notes = "Agent locator.")
  public static final String AGENT = "agent";
  @LocatorDimension(value = "agentName", format = LocatorDimensionDataType.STRING, notes = "Agent name (experimental).", hidden = true)
  public static final String AGENT_NAME = "agentName";
  @LocatorDimension(value = "agentTypeId", dataType = LocatorDimensionDataType.INTEGER, notes = "typeId of agent used to execute build.")
  public static final String AGENT_TYPE_ID = "agentTypeId";
  @LocatorDimension(value = "personal", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is a personal build.")
  public static final String PERSONAL = "personal";
  @LocatorDimension(value = "user", format = LocatorName.USER, notes = "For personal builds checks the owner of the build, triggerring user in other cases.")
  public static final String USER = "user";
  @LocatorDimension(value = "triggered", format = LocatorDimensionDataType.STRING, notes = "Triggered by (experimental).", hidden = true)
  public static final String TRIGGERED = "triggered";
  @LocatorDimension(value = "branch", format = LocatorName.BRANCH, notes = "Branch locator.")
  protected static final String BRANCH = "branch";
  @LocatorDimension(value = "branched", format = LocatorDimensionDataType.STRING, notes = "Build in a branch (experimental).", hidden = true)
  protected static final String BRANCHED = "branched";
  // @LocatorDimension(value = "property", notes = "Build in a branch (experimental).", hidden = true)
  //
  protected static final String PROPERTY = "property";
  @LocatorDimension(value = "statisticValue", format = LocatorDimensionDataType.STRING, notes = "Build in a branch (experimental).", hidden = true)
  protected static final String STATISTIC_VALUE = "statisticValue";
  @LocatorDimension(value = "state", allowableValues = "queued,running,finished,any")
  public static final String STATE = "state";
  @LocatorDimension(value = "queued", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is queued.")
  public static final String STATE_QUEUED = "queued";
  @LocatorDimension(value = "running", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is running.")
  public static final String STATE_RUNNING = "running";
  @LocatorDimension(value = "finished", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is finished.")
  public static final String STATE_FINISHED = "finished";
  @LocatorDimension(value = "any", dataType = LocatorDimensionDataType.BOOLEAN, notes = "State can be any.")
  protected static final String STATE_ANY = "any";

  @LocatorDimension(value = "number", notes = "Build number string.")
  protected static final String NUMBER = "number";
  @LocatorDimension(value = "status", notes = "Status text.")
  protected static final String STATUS = "status";
  @LocatorDimension(value = "canceled", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is canceled.")
  protected static final String CANCELED = "canceled";
  @LocatorDimension(value = "failedToStart", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is failed to start.")
  protected static final String FAILED_TO_START = "failedToStart";
  @LocatorDimension(value = "pinned", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is pinned.")
  protected static final String PINNED = "pinned";
  @LocatorDimension(value = "running", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is in running state (compatibility with TC 9.0).", hidden = true)
  protected static final String RUNNING = "running";
  @LocatorDimension(value = "hanging", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is hanging.")
  protected static final String HANGING = "hanging";
  @LocatorDimension(value = "composite", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is composite.")
  protected static final String COMPOSITE = "composite";
  @LocatorDimension("snapshotDependency")
  protected static final String SNAPSHOT_DEP = "snapshotDependency";
  @LocatorDimension("artifactDependency")
  protected static final String ARTIFACT_DEP = "artifactDependency";
  @LocatorDimension(value = "snapshotDependencyProblem", notes = "Experimental", hidden = true)
  public static final String SNAPSHOT_PROBLEM = "snapshotDependencyProblem";
  protected static final String COMPATIBLE_AGENTS_COUNT = "compatibleAgentsCount";
  @LocatorDimension(value = "tags", notes = "legacy support only", hidden = true)
  protected static final String TAGS = "tags";
  @LocatorDimension(value = "tag", format = LocatorName.TAG, notes = "Tag locator.")
  protected static final String TAG = "tag";
  @LocatorDimension(value = "compatibleAgent", format = LocatorName.AGENT, notes = "Agent locator.")
  protected static final String COMPATIBLE_AGENT = "compatibleAgent";
  @LocatorDimension(value = "history", dataType = LocatorDimensionDataType.BOOLEAN, notes = "Is history build.")
  protected static final String HISTORY = "history";
  @LocatorDimension(value = "testOccurrence", notes = "Experimental", hidden = true)
  protected static final String TEST_OCCURRENCE = "testOccurrence";
  @LocatorDimension(value = "test", notes = "Experimental", hidden = true)
  protected static final String TEST = "test";
  //todo: add problem* filtering; filtering by statusText;
  @LocatorDimension(value = "sinceBuild", notes = "Experimental, use startDate:(build:(<locator>),condition:after) instead", hidden = true)
  protected static final String SINCE_BUILD = "sinceBuild"; //
  @LocatorDimension(value = "sinceDate", notes = "Experimental, use startDate:(date:<date>,condition:after) instead", hidden = true)
  protected static final String SINCE_DATE = "sinceDate";
  @LocatorDimension(value = "untilBuild", notes = "Experimental, use startDate:(build:(<locator>),condition:before) instead", hidden = true)
  protected static final String UNTIL_BUILD = "untilBuild";
  @LocatorDimension(value = "untilDate", notes = "Experimental, use startDate:(date:<date>,condition:before) instead", hidden = true)
  protected static final String UNTIL_DATE = "untilDate";

  @LocatorDimension(value = "queuedDate", format = "date:<yyyyMMddTHHmmss+ZZZZ>,build:<build locator>,condition:<before/after>", notes = "Requires either date or build dimension.")
  protected static final String QUEUED_TIME = "queuedDate";
  @LocatorDimension(value = "startDate", format = "date:<yyyyMMddTHHmmss+ZZZZ>,build:<build locator>,condition:<before/after>", notes = "Requires either date or build dimension.")
  protected static final String STARTED_TIME = "startDate";
  @LocatorDimension(value = "finishDate", format = "date:<yyyyMMddTHHmmss+ZZZZ>,build:<build locator>,condition:<before/after>", notes = "Requires either date or build dimension.")
  protected static final String FINISHED_TIME = "finishDate";

  @LocatorDimension(value = "defaultFilter", dataType = LocatorDimensionDataType.BOOLEAN, notes = "If true, applies default filter which returns only \"normal\" builds (finished builds which are not canceled, not failed-to-start, not personal, and on default branch (in branched build configurations)).")
  protected static final String DEFAULT_FILTERING = "defaultFilter";
  @LocatorDimension(value = "sinceBuildIdLookAheadCount", notes = "Experimental", hidden = true)
  protected static final String SINCE_BUILD_ID_LOOK_AHEAD_COUNT = "sinceBuildIdLookAheadCount";  /*experimental*/
  @LocatorDimension(value = "ordered", notes = "Experimental", hidden = true)
  public static final String ORDERED = "ordered"; /*experimental*/
  @LocatorDimension(value = "strob", notes = "Experimental", hidden = true)
  public static final String STROB = "strob"; /*experimental*/  //might need a better name

  @LocatorDimension(value = "byPromotion", notes = "Legacy", hidden = true)
  public static final String BY_PROMOTION = "byPromotion";  //used in BuildFinder
  @LocatorDimension(value = "equivalent", notes = "Experimental", hidden = true)
  public static final String EQUIVALENT = "equivalent"; /*experimental*/
  @LocatorDimension(value = "metadata", notes = "Experimental", hidden = true)
  public static final String METADATA = "metadata"; /*experimental*/

  @LocatorDimension(value = "revision", notes = "Build revision.")
  public static final String REVISION = "revision";
  //todo: filter by modId/chainModId (including null) - before(up to)/after filtering

  protected static final String STROB_BUILD_LOCATOR = "locator";
  protected static final String LEGACY_BUILDS_FILTERING = "rest.request.builds.useLegacyBuildsFiltering";
  protected static final String LEGACY_BUILDS_FILTERING_FORCED = "rest.request.builds.useLegacyBuildsFiltering.forced"; //Since 2018.1
  public static final BuildPromotionComparator BUILD_PROMOTIONS_COMPARATOR = new BuildPromotionComparator();
  public static final SnapshotDepsTraverser SNAPSHOT_DEPENDENCIES_TRAVERSER = new SnapshotDepsTraverser();
  private final SnapshotDepProblemsTraverser mySnapshotDepProblemsTraverser;
  private final Finder<TriggeredBy> myTriggerByFinder;

  private final BuildPromotionManager myBuildPromotionManager;
  private final BuildQueue myBuildQueue;
  private final BuildsManager myBuildsManager;
  private final VcsRootFinder myVcsRootFinder;
  private final ProjectFinder myProjectFinder;
  private final BuildTypeFinder myBuildTypeFinder;
  private final UserFinder myUserFinder;
  private final AgentFinder myAgentFinder;
  private final BranchFinder myBranchFinder;
  private final MetadataStorageEx myMetadataStorage;
  private final TimeCondition myTimeCondition;
  private final PermissionChecker myPermissionChecker;
  @NotNull private final ServiceLocator myServiceLocator;

  @NotNull
  public static String getLocator(@NotNull final BuildPromotion buildPromotion) {
    return Locator.getStringLocator(DIMENSION_ID, String.valueOf(getBuildId(buildPromotion)));
  }

  @NotNull
  @Override
  public String getItemLocator(@NotNull final BuildPromotion buildPromotion) {
    return BuildPromotionFinder.getLocator(buildPromotion);
  }

  public BuildPromotionFinder(final BuildPromotionManager buildPromotionManager,
                              final BuildQueue buildQueue,
                              final BuildsManager buildsManager,
                              final VcsRootFinder vcsRootFinder,
                              final ProjectFinder projectFinder,
                              final BuildTypeFinder buildTypeFinder,
                              final UserFinder userFinder,
                              final AgentFinder agentFinder,
                              final BranchFinder branchFinder,
                              final TimeCondition timeCondition,
                              final PermissionChecker permissionChecker,
                              final MetadataStorageEx metadataStorage,
                              @NotNull final ServiceLocator serviceLocator) {
    super(DIMENSION_ID, PROMOTION_ID, PROJECT, AFFECTED_PROJECT, BUILD_TYPE, BRANCH, AGENT, AGENT_NAME, AGENT_TYPE_ID, USER, PERSONAL, STATE, TAG, PROPERTY, COMPATIBLE_AGENT, NUMBER, STATUS, CANCELED,
          PINNED, QUEUED_TIME, STARTED_TIME, FINISHED_TIME, SINCE_BUILD, SINCE_DATE, UNTIL_BUILD, UNTIL_DATE, FAILED_TO_START, SNAPSHOT_DEP, ARTIFACT_DEP, HANGING, COMPOSITE, HISTORY,
          DEFAULT_FILTERING, SINCE_BUILD_ID_LOOK_AHEAD_COUNT,
          Locator.LOCATOR_SINGLE_VALUE_UNUSED_NAME);
    setHiddenDimensions(TAGS, RUNNING,  //compatibility
                        BY_PROMOTION,  //switch for legacy behavior
                        COMPATIBLE_AGENTS_COUNT,  //experimental for queued builds only
                        EQUIVALENT, REVISION, PROMOTION_ID_ALIAS, BUILD_ID, METADATA,
                        STATISTIC_VALUE, TEST_OCCURRENCE, TEST,  //experimental
                        SINCE_BUILD_ID_LOOK_AHEAD_COUNT,  //experimental
                        ORDERED,  //experimental
                        STROB,  //experimental
                        BRANCHED,  //experimental
                        TRIGGERED,  //experimental
                        SNAPSHOT_PROBLEM //experimental
    );

    myPermissionChecker = permissionChecker;
    myBuildPromotionManager = buildPromotionManager;
    myBuildQueue = buildQueue;
    myBuildsManager = buildsManager;
    myVcsRootFinder = vcsRootFinder;
    myProjectFinder = projectFinder;
    myBuildTypeFinder = buildTypeFinder;
    myUserFinder = userFinder;
    myAgentFinder = agentFinder;
    myBranchFinder = branchFinder;
    myMetadataStorage = metadataStorage;
    myTimeCondition = timeCondition;
    myServiceLocator = serviceLocator;

    myTriggerByFinder = getTriggeredByFinder(myTimeCondition, myServiceLocator);
    mySnapshotDepProblemsTraverser = new SnapshotDepProblemsTraverser(this);
  }

  @NotNull
  public static String getLocator(@NotNull final SBuildType buildType, @Nullable final Branch branch, @Nullable final String additionalLocator){
    String result = Locator.getStringLocator(BUILD_TYPE, BuildTypeFinder.getLocator(buildType));
    if (branch != null) {
      result = LocatorUtil.setDimension(result, BRANCH, BranchFinder.getLocator(branch));
    }
    if (additionalLocator == null) return result;
    return Locator.merge(result, additionalLocator);
  }

  @NotNull
  public static String getLocator(@NotNull final SBuildAgent agent){
    return Locator.getStringLocator(AGENT, AgentFinder.getLocator(agent));
  }

  @Override
  public Long getDefaultPageItemsCount() {
    return (long)Constants.getDefaultPageItemsCount();
  }

  @Override
  public Long getDefaultLookupLimit() {
    final long defaultLookupLimit = TeamCityProperties.getLong("rest.request.builds.defaultLookupLimit", 5000);
    if (defaultLookupLimit != 0) {
      return defaultLookupLimit;
    }
    return null;
  }

  @NotNull
  @Override
  public DuplicateChecker<BuildPromotion> createDuplicateChecker() {
    return new KeyDuplicateChecker<>(BuildPromotion::getId);
  }

  @Nullable
  @Override
  public BuildPromotion findSingleItem(@NotNull final Locator locator) {
    if (locator.isSingleValue()) {
      final Long singleValueAsLong = locator.getSingleValueAsLong();
      // difference from 9.0 behavior where we never searched by promotion id in case of single value locators
      assert singleValueAsLong != null;
      return getBuildPromotionByIdOrByBuildId(singleValueAsLong);
    }

    Long promotionId = locator.getSingleDimensionValueAsLong(PROMOTION_ID);
    if (promotionId == null){
      promotionId = locator.getSingleDimensionValueAsLong(PROMOTION_ID_ALIAS); //support TeamCity 8.0 dimension
    }
    if (promotionId != null) {
      return getBuildPromotion(promotionId);
    }

    Long buildId = locator.getSingleDimensionValueAsLong(BUILD_ID);
    if (buildId != null) {
      final SBuild build = myBuildsManager.findBuildInstanceById(buildId);
      if (build != null) {
        return build.getBuildPromotion();
      }
      throw new NotFoundException("No build found by build id '" + buildId + "'.");
    }

    final Long id = locator.getSingleDimensionValueAsLong(DIMENSION_ID);
    if (id != null) {
      return getBuildPromotionByIdOrByBuildId(id);
    }

    return null;
  }

  @NotNull
  @Override
  public ItemFilter<BuildPromotion> getFilter(@NotNull final Locator locator) {
    final MultiCheckerFilter<BuildPromotion> result = new MultiCheckerFilter<>();

    //checking permissions to view - workaround for TW-45544
    result.add(item -> {
      try {
        ensureCanView(item);
        return true;
      } catch (AccessDeniedException e) {
        return false; //excluding from the lists as secure wrappers usually do
      }
    });

    if (locator.isSingleValue()) {
      try {
        long foundPromotionId = getBuildPromotionByIdOrByBuildId(locator.getSingleValueAsLong()).getId();
        result.add(item -> foundPromotionId == item.getId());
      } catch (NotFoundException e) {
        result.add(item -> false);
      }
    }

    if (locator.isUnused(DEFAULT_FILTERING)) {
      //basically, mark as used if it is not yet processed, but is unset or is set to false
      final Boolean defaultFiltering = locator.getSingleDimensionValueAsBoolean(DEFAULT_FILTERING);
      if (defaultFiltering != null && defaultFiltering) {
        locator.markUnused(DEFAULT_FILTERING);
      }
    }

    final Long id = locator.getSingleDimensionValueAsLong(DIMENSION_ID);
    if (id != null) {
      try {
        long foundPromotionId = getBuildPromotionByIdOrByBuildId(id).getId();
        result.add(item -> foundPromotionId == item.getId());
      } catch (NotFoundException e) {
        result.add(item -> false);
      }
    }
    final Long promotionId = locator.getSingleDimensionValueAsLong(PROMOTION_ID);
    if (promotionId != null) {
      try {
        long foundPromotionId = getBuildPromotion(promotionId).getId();
        result.add(item -> foundPromotionId == item.getId());
      } catch (NotFoundException e) {
        result.add(item -> false);
      }
    }
    final Long promotionIdAlias = locator.getSingleDimensionValueAsLong(PROMOTION_ID_ALIAS);
    if (promotionIdAlias != null) {
      try {
        long foundPromotionId = getBuildPromotion(promotionIdAlias).getId();
        result.add(item -> foundPromotionId == item.getId());
      } catch (NotFoundException e) {
        result.add(item -> false);
      }
    }
    final Long buildId = locator.getSingleDimensionValueAsLong(BUILD_ID);
    if (buildId != null) {
      result.add(item -> buildId.equals(item.getAssociatedBuildId()));
    }

    Locator stateLocator = getStateLocator(locator);

    if (!isStateIncluded(stateLocator, STATE_QUEUED)) {
      result.add(item -> item.getQueuedBuild() == null);
    }

    if (!isStateIncluded(stateLocator, STATE_RUNNING)) {
      result.add(item -> {
        final SBuild associatedBuild = item.getAssociatedBuild();
        return associatedBuild == null || associatedBuild.isFinished();
      });
    }

    if (!isStateIncluded(stateLocator, STATE_FINISHED)) {
      result.add(item -> {
        final SBuild associatedBuild = item.getAssociatedBuild();
        return associatedBuild == null || !associatedBuild.isFinished();
      });
    }

    final Boolean composite = locator.getSingleDimensionValueAsBoolean(COMPOSITE);
    if (composite != null) {
      result.add(item -> FilterUtil.isIncludedByBooleanFilter(composite, item.isCompositeBuild()));
    }

    if (locator.isUnused(PROJECT)) {
      SProject project = getProjectFromDimension(locator, PROJECT);
      if (project != null) {
        result.add(item -> {
          final SBuildType buildType = item.getBuildType();
          return buildType != null && project.equals(buildType.getProject());
        });
      }
    }

    if (locator.isUnused(AFFECTED_PROJECT)) {
      SProject affectedProject = getProjectFromDimension(locator, AFFECTED_PROJECT);
      if (affectedProject != null && !affectedProject.isRootProject()) {
        result.add(item -> {
          final SBuildType buildType = item.getBuildType();
          return buildType != null && ProjectFinder.isSameOrParent(affectedProject, buildType.getProject());
        });
      }
    }

    if (locator.isUnused(BUILD_TYPE)) {
      final String buildTypeLocator = locator.getSingleDimensionValue(BUILD_TYPE);
      if (buildTypeLocator != null) {
        final Set<SBuildType> buildTypes = new HashSet<>(myBuildTypeFinder.getBuildTypes(getProjectFromDimension(locator, PROJECT), buildTypeLocator));
        if (buildTypes.isEmpty()) {
          throw new NotFoundException("No build types found for locator '" + buildTypeLocator + "'");
        }
        //todo: use build types Filter instead
        result.add(item -> buildTypes.contains(item.getParentBuildType()));
      }
    }

    final String branchLocatorValue = locator.getSingleDimensionValue(BRANCH);
    if (branchLocatorValue != null) {
      PagedSearchResult<? extends Branch> branches = null;
      BadRequestException exceptionOnFullSearch = null;
      try {
        branches = myBranchFinder.getItemsIfValidBranchListLocator(locator.lookupSingleDimensionValue(BUILD_TYPE), branchLocatorValue);
      } catch (BadRequestException e) {
        // not a valid branches listing locator
        exceptionOnFullSearch = e;
      }
      if (branches != null) {
        //branches found - use them
        Set<String> branchNames = getBranchNamesSet(branches.myEntries);
        Set<String> branchDisplayNames = getBranchDisplayNamesSet(branches.myEntries);
        boolean defaultBranchIncluded = branches.myEntries.stream().anyMatch(Branch::isDefaultBranch);
        result.add(item -> {
            final Branch buildBranch = BranchData.fromBuild(item);
            return (defaultBranchIncluded && buildBranch.isDefaultBranch()) || branchNames.contains(buildBranch.getName()) || branchDisplayNames.contains(buildBranch.getDisplayName());
        });
      } else {
        //branches not found by locator - try to use filter
        BranchFinder.BranchFilterDetails branchFilterDetails;
        try {
          branchFilterDetails = myBranchFinder.getBranchFilterDetails(branchLocatorValue);
        } catch (LocatorProcessException locatorException) {
          throw new BadRequestException("Invalid sub-locator '" + BRANCH + "': Cannot" +
                                        (exceptionOnFullSearch != null ? " find branches: " + exceptionOnFullSearch.getMessage() +"; and cannot" : "") +
                                        " create filter: " + locatorException.getMessage(), locatorException);
        }
        if (!branchFilterDetails.isAnyBranch()) {
          result.add(item -> branchFilterDetails.isIncluded(item));
        }
      }
    }

    final Boolean branched = locator.getSingleDimensionValueAsBoolean(BRANCHED);
    if (branched != null) {
      result.add(item -> FilterUtil.isIncludedByBooleanFilter(branched, item.getBranch() != null));
    }

    if (locator.isUnused(AGENT)) {
      final String agentLocator = locator.getSingleDimensionValue(AGENT);
      if (agentLocator != null) {
        Set<Integer> agentIds = myAgentFinder.getItemsNotEmpty(agentLocator).myEntries.stream().map(agent -> agent.getId()).filter(i -> i != Agent.UNKNOWN_AGENT_ID).collect(Collectors.toSet());
        result.add(item -> {
          final SQueuedBuild queuedBuild = item.getQueuedBuild(); //for queued build using compatible agents
          if (queuedBuild != null) {
            return queuedBuild.getCanRunOnAgents().stream().anyMatch(agent -> agentIds.contains(agent.getId()));
          }

          final SBuild build = item.getAssociatedBuild();
          if (build != null) {
            return agentIds.contains(build.getAgent().getId());
          }
          return false;
        });
      }
    }

    //compatibility support
    final String tags = locator.getSingleDimensionValue(TAGS);
    if (tags != null) {
      final List<String> tagsList = Arrays.asList(tags.split(","));
      if (tagsList.size() > 0) {
        result.add(item -> item.getTags().containsAll(tagsList));
      }
    }

    if(locator.isUnused(TAG)) {
      result.add(getFilterByTag(locator.getDimensionValue(TAG)));
    }

    final String compatibleAgentLocator = locator.getSingleDimensionValue(COMPATIBLE_AGENT);
    if (compatibleAgentLocator != null) {
      List<SBuildAgent> agents = myAgentFinder.getItems(compatibleAgentLocator).myEntries;
      result.add(build -> agents.stream().anyMatch(agent -> myAgentFinder.canActuallyRun(agent, build)));
    }

    final Long compatibleAgentsCount = locator.getSingleDimensionValueAsLong(COMPATIBLE_AGENTS_COUNT); //experimental, only for queued builds
    if (compatibleAgentsCount != null) {
      result.add(item -> {
          long count = 0;
          for (SBuildAgent agent : myAgentFinder.getItems(null).myEntries) { //or should process unauthorized as well?
            if (myAgentFinder.canActuallyRun(agent, item)) count++;
            if (count > compatibleAgentsCount) return false;
          }
          return count == compatibleAgentsCount;
      });
    }

    final Boolean personal = locator.getSingleDimensionValueAsBoolean(PERSONAL);
    if (personal != null) {
      result.add(item -> FilterUtil.isIncludedByBooleanFilter(personal, item.isPersonal()));
    }

    final Boolean history = locator.getSingleDimensionValueAsBoolean(HISTORY);
    if (history != null) {
      result.add(item -> FilterUtil.isIncludedByBooleanFilter(history, item.isOutOfChangesSequence()));
    }

    final String userDimension = locator.getSingleDimensionValue(USER);
    if (userDimension != null) {
      final SUser user = myUserFinder.getItem(userDimension);
      result.add(item -> {
          SUser owner = item.getOwner();
          if (owner != null) {
            // if owner is present, consider only it: this is consistent with the builds search in the prefiltering
            return user.getId() == owner.getId();
          }
          SUser actualUser = Util.resolveNull(getTriggeredBy(item), TriggeredBy::getUser);
          return actualUser != null && user.getId() == actualUser.getId();
      });
    }

    final String triggeredDimension = locator.getSingleDimensionValue(TRIGGERED);
    if (triggeredDimension != null) {
      final ItemFilter<TriggeredBy> filter = myTriggerByFinder.getFilter(triggeredDimension);
      result.add(item -> Util.resolveNull(getTriggeredBy(item), filter::isIncluded, false));
    }

    final List<String> properties = locator.getDimensionValue(CommonLocatorDimensionsList.PROPERTY);
    if (!properties.isEmpty()) {
      final Matcher<ParametersProvider> parameterCondition = ParameterCondition.create(properties);
      result.add(item -> {
          if (!Build.canViewRuntimeData(myPermissionChecker, item)) return false;
          //does not correspond to Build.getProperties() which includes less parameters
          return parameterCondition.matches(Build.getBuildResultingParameters(item, myServiceLocator)); //TeamCity open API issue
      });
    }

    if (locator.getUnusedDimensions().contains(SNAPSHOT_DEP)) { //performance optimization: do not filter if already processed
      final String snapshotDepDimension = locator.getSingleDimensionValue(SNAPSHOT_DEP);
      if (snapshotDepDimension != null) {
        final Set<BuildPromotion> snapshotRelatedBuilds = new HashSet<>(getSnapshotRelatedBuilds(snapshotDepDimension));
        result.add(item -> snapshotRelatedBuilds.contains(item));
      }
    }

    if (locator.getUnusedDimensions().contains(ARTIFACT_DEP)) { //performance optimization: do not filter if already processed
      final String artifactDepDimension = locator.getSingleDimensionValue(ARTIFACT_DEP);
      if (artifactDepDimension != null) {
        final Set<BuildPromotion> artifactRelatedBuilds = new HashSet<>(getArtifactRelatedBuilds(artifactDepDimension, locator));
        result.add(item -> artifactRelatedBuilds.contains(item));
      }
    }

    if (locator.isUnused(SNAPSHOT_PROBLEM)) {
      final String snapshotDepProblem = locator.getSingleDimensionValue(SNAPSHOT_PROBLEM);
      if (snapshotDepProblem != null) {
        final Set<BuildPromotion> snapshotDepProblemBuilds = new HashSet<>(getSnapshotDepProblemBuilds(snapshotDepProblem));
        result.add(item -> snapshotDepProblemBuilds.contains(item));
      }
    }

    if (locator.getUnusedDimensions().contains(EQUIVALENT)) { //performance optimization: do not filter if already processed
      final String equivalent = locator.getSingleDimensionValue(EQUIVALENT);
      if (equivalent != null) {
        final Set<BuildPromotion> filter = new HashSet<>(((BuildPromotionEx)getItem(equivalent)).getStartedEquivalentPromotions(-1));
        result.add(item -> filter.contains(item));
      }
    }

    if (locator.getUnusedDimensions().contains(METADATA)) { //performance optimization: do not filter if already processed
      final String metadata = locator.getSingleDimensionValue(METADATA);
      if (metadata != null) {
        final Iterator<BuildMetadataEntry> metadataEntries = getBuildMetadataEntryIterator(metadata);
        final Set<Long> buildIds = new HashSet<>();
        while (metadataEntries.hasNext()) {
          BuildMetadataEntry metadataEntry = metadataEntries.next();
          buildIds.add(metadataEntry.getBuildId());
        }
        result.add(item -> {
          if (!Build.canViewRuntimeData(myPermissionChecker, item)) return false;
          return buildIds.contains(item.getAssociatedBuildId());
        });
      }
    }

    if (locator.getUnusedDimensions().contains(ORDERED)) { //performance optimization: do not filter if already processed
      final String graphLocator = locator.getSingleDimensionValue(ORDERED);
      if (graphLocator != null) {
        final GraphFinder<BuildPromotion> graphFinder = new BuildPromotionOrderedFinder(BuildPromotionFinder.this);
        final Set<BuildPromotion> filter = new HashSet<>(graphFinder.getItems(graphLocator).myEntries);
        result.add(filter::contains);
      }
    }

    final String sinceBuildDimension = locator.getSingleDimensionValue(SINCE_BUILD);
    BuildPromotion sinceBuildPromotion = null;
    Long sinceBuildId = null;
    if (sinceBuildDimension != null) {
      try {
        sinceBuildPromotion = getItem(sinceBuildDimension);
        final SQueuedBuild queuedBuild = sinceBuildPromotion.getQueuedBuild();
        if (queuedBuild != null) {
          //compare queued builds by id (triggering sequence)
          final long buildPromotionId = getBuildId(sinceBuildPromotion);
          result.add(item -> buildPromotionId < getBuildId(item));
        } else {
          // for started build, compare by start time
          final SBuild limitingBuild = sinceBuildPromotion.getAssociatedBuild();
          if (limitingBuild != null) {
            final Date startDate = limitingBuild.getStartDate();
            result.add(item -> {
                final SBuild build = item.getAssociatedBuild();
                if (build == null) return true;
                if (startDate.equals(build.getStartDate()) && limitingBuild.getBuildId() != build.getBuildId()) return true;
                return startDate.before(build.getStartDate());
            });
          }
        }
      } catch (NotFoundException e) {
        //build not found by sinceBuild locator, extract id ad filter using it
        sinceBuildId = getBuildId(sinceBuildDimension);
        final long sinceBuildIdFinal = sinceBuildId;
        result.add(item -> sinceBuildIdFinal < getBuildId(item));
      }
    }

    final String untilBuild = locator.getSingleDimensionValue(UNTIL_BUILD);
    if (untilBuild != null) {
      try {
        final BuildPromotion untilBuildPromotion = getItem(untilBuild);
        final SQueuedBuild queuedBuild = untilBuildPromotion.getQueuedBuild();
        if (queuedBuild != null) {
          //compare queued builds by id (triggering sequence)
          final long buildPromotionId = getBuildId(untilBuildPromotion);
          result.add(item -> buildPromotionId >= getBuildId(item));
        } else {
          // for started build, compare by start time
          final SBuild limitingBuild = untilBuildPromotion.getAssociatedBuild();
          if (limitingBuild != null) {
            final Date startDate = limitingBuild.getStartDate();
            result.add(item -> {
                final SBuild build = item.getAssociatedBuild();
                return build == null || !startDate.before(build.getStartDate());
            });
          }
        }
      } catch (NotFoundException e) {
        //build not found by sinceBuild locator, extract id ad filter using it
        final long untilBuildId = getBuildId(untilBuild);
        result.add(item -> untilBuildId >= getBuildId(item));
      }
    }

    TimeCondition.FilterAndLimitingDate<BuildPromotion> queuedFiltering =
      myTimeCondition.processTimeConditions(QUEUED_TIME, locator, TimeCondition.QUEUED_BUILD_TIME, TimeCondition.QUEUED_BUILD_TIME);
    if (queuedFiltering != null) result.add(queuedFiltering.getFilter());

    TimeCondition.FilterAndLimitingDate<BuildPromotion> startedFiltering =
      myTimeCondition.processTimeConditions(STARTED_TIME, locator, TimeCondition.STARTED_BUILD_TIME, TimeCondition.STARTED_BUILD_TIME);

    @Nullable Date sinceStartDate = null;
    if (startedFiltering != null) {
      result.add(startedFiltering.getFilter());
      sinceStartDate = startedFiltering.getLimitingDate();
    }

    //todo: add processing cut of based on assumption of max build time (say, a week); for other times as well
    TimeCondition.FilterAndLimitingDate<BuildPromotion> finishFiltering =
      myTimeCondition.processTimeConditions(FINISHED_TIME, locator, TimeCondition.FINISHED_BUILD_TIME, TimeCondition.FINISHED_BUILD_TIME);
    if (finishFiltering != null) result.add(finishFiltering.getFilter());

    final String revisionLocatorText = locator.getSingleDimensionValue(REVISION);
    if (revisionLocatorText != null) {
      final Locator revisionLocator = new Locator(revisionLocatorText, "version", "internalVersion", "vcsRoot", Locator.LOCATOR_SINGLE_VALUE_UNUSED_NAME);
      final String revision = revisionLocator.getSingleValue();
      if (revision != null) {
        result.add(item -> {
            final List<BuildRevision> buildRevisions = item.getRevisions(); //use getAllrevisions map??
            for (BuildRevision rev : buildRevisions) {
              if (revision.equals(rev.getRevisionDisplayName())) {
                return true;
              }
            }
            return false;
        });
      } else {
        final String vcsRootLocator = revisionLocator.getSingleDimensionValue("vcsRoot");
        final SVcsRoot vcsRoot = vcsRootLocator == null ? null : myVcsRootFinder.getItem(vcsRootLocator);
        final ValueCondition versionCondition = ParameterCondition.createValueCondition(revisionLocator.getSingleDimensionValue("version"));
        final ValueCondition internalVersionCondition = ParameterCondition.createValueCondition(revisionLocator.getSingleDimensionValue("internalVersion"));
        revisionLocator.checkLocatorFullyProcessed();
        if (vcsRoot != null || versionCondition != null || internalVersionCondition != null) {
          result.add(item -> {
              final List<BuildRevision> revisions = item.getRevisions();  //use getAllrevisions map??
              for (BuildRevision rev : revisions) {
                if ((vcsRoot == null || vcsRoot.getId() == rev.getRoot().getParent().getId()) &&
                    (versionCondition == null || versionCondition.matches(rev.getRevisionDisplayName())) &&
                    (internalVersionCondition == null || internalVersionCondition.matches(rev.getRevision()))) {
                  return true;
                }
              }
              return false;
          });
        }
      }
    }

    final MultiCheckerFilter<SBuild> buildFilter = getBuildFilter(locator);
    if (buildFilter.getSubFiltersCount() > 0) {
      result.add(item -> {
          final SBuild build = item.getAssociatedBuild();
          if (build == null) {
            return false;
          }
          return buildFilter.isIncluded(build);
      });
    }

    sinceStartDate = TimeCondition.maxDate(sinceStartDate, DataProvider.parseDate(locator.getSingleDimensionValue(SINCE_DATE))); //see also filtering in getBuildFilter

    final Boolean canceled = locator.getSingleDimensionValueAsBoolean(CANCELED);
    if (canceled != null) {
      result.add(item -> {
          final SBuild build = item.getAssociatedBuild();
          return FilterUtil.isIncludedByBooleanFilter(canceled, build != null && build.getCanceledInfo() != null);
      });
    }

    final Boolean failedToStart = locator.getSingleDimensionValueAsBoolean(FAILED_TO_START);
    if (failedToStart != null) {
      result.add(item -> {
          final SBuild build = item.getAssociatedBuild();
          return FilterUtil.isIncludedByBooleanFilter(failedToStart, build != null && build.isInternalError());
      });
    }

    return getFilterWithProcessingCutOff(result, locator.getSingleDimensionValueAsLong(SINCE_BUILD_ID_LOOK_AHEAD_COUNT), sinceBuildPromotion, sinceBuildId, sinceStartDate);
  }

  private FilterConditionChecker<BuildPromotion> getFilterByTag(@NotNull final List<String> tags) {
    if (tags.isEmpty()) {
      return item -> true;
    }
    if (tags.size() == 1 && tags.get(0).startsWith("format:extended")) { //pre-9.1 compatibility
      //todo: log this?
      return item -> {
        try {
          return isTagsMatchLocator(item.getTags(), new Locator(tags.get(0)));
        } catch (LocatorProcessException e) {
          throw new BadRequestException("Invalid locator 'tag' (legacy format is used): " + e.getMessage(), e);
        }
      };
    }

    MultiCheckerFilter<BuildPromotion> filterByEachTag = new MultiCheckerFilter<>();
    for (String singleTag : tags) {
      filterByEachTag.add(item -> TagFinder.isIncluded(item, singleTag, myUserFinder));
    }

    return filterByEachTag;
  }

  @Nullable
  private static TriggeredBy getTriggeredBy(@NotNull final BuildPromotion buildPromotion) {
    final SBuild build = buildPromotion.getAssociatedBuild();
    if (build != null) return build.getTriggeredBy();

    final SQueuedBuild queuedBuild = buildPromotion.getQueuedBuild();
    if (queuedBuild != null) return queuedBuild.getTriggeredBy();
    return null;
  }

  @NotNull
  private static Finder<TriggeredBy> getTriggeredByFinder(@NotNull final TimeCondition timeCondition, @NotNull final ServiceLocator serviceLocator) {
    TypedFinderBuilder<TriggeredBy> builder = new TypedFinderBuilder<>();
    builder.dimensionTimeCondition(new TypedFinderBuilder.Dimension<>("date"), timeCondition).description("timestamp of the triggering")
           .valueForDefaultFilter(TriggeredBy::getTriggeredDate);
    builder.dimensionUsers(new TypedFinderBuilder.Dimension<>("user"), serviceLocator).description("user who triggered")
           .valueForDefaultFilter(triggeredBy -> Collections.singleton(triggeredBy.getUser()));
    builder.dimensionValueCondition(new TypedFinderBuilder.Dimension<>("type")).description("type of the trigger")
           .valueForDefaultFilter(item -> jetbrains.buildServer.server.rest.model.build.TriggeredBy.getDetails(item, serviceLocator).type);
    return builder.build();
  }

  @Nullable
  private SProject getProjectFromDimension(final @NotNull Locator locator, @NotNull final String dimension) {
    final String projectLocator = locator.getSingleDimensionValue(dimension);
    if (projectLocator == null) {
      return null;
    }
    return myProjectFinder.getItem(projectLocator); //todo: support multiple
  }

  private Set<String> getBranchNamesSet(final List<? extends Branch> branches) {
    final HashSet<String> result = new HashSet<>(branches.size());
    for (Branch branch : branches) {
      result.add(branch.getName());
    }
    return result;
  }

  private Set<String> getBranchDisplayNamesSet(final List<? extends Branch> branches) {
    final HashSet<String> result = new HashSet<>(branches.size());
    for (Branch branch : branches) {
      result.add(branch.getDisplayName());
    }
    return result;
  }

  private ItemFilter<BuildPromotion> getFilterWithProcessingCutOff(@NotNull final MultiCheckerFilter<BuildPromotion> result,
                                                                       @Nullable final Long lookupLimit,
                                                                       @Nullable final BuildPromotion sinceBuildPromotion,
                                                                       @Nullable Long sinceBuildId,
                                                                       @Nullable Date sinceStartDate) {
    if (sinceBuildPromotion == null && sinceBuildId == null && sinceStartDate == null) {
      return result;
    }

    if (sinceBuildPromotion != null) {
      sinceBuildId = sinceBuildId != null ? Math.max(sinceBuildId, getBuildId(sinceBuildPromotion)) : getBuildId(sinceBuildPromotion);
      final SBuild sinceBuild = sinceBuildPromotion.getAssociatedBuild();
      if (sinceBuild != null) {
        sinceStartDate = TimeCondition.maxDate(sinceStartDate, sinceBuild.getStartDate());
      }
    }

    //cut off builds traversing
    final long lookAheadCount = lookupLimit != null ? lookupLimit : TeamCityProperties.getLong("rest.request.builds.sinceBuildIdLookAheadCount", 50);
    final Long sinceBuildIdFinal = sinceBuildId;
    final Date sinceStartDateFinal = sinceStartDate;
    return new ItemFilter<BuildPromotion>() {
      private long currentLookAheadCount = 0;

      public boolean isIncluded(@NotNull final BuildPromotion item) {
        return result.isIncluded(item);
      }

      public boolean shouldStop(@NotNull final BuildPromotion item) {
        if (result.shouldStop(item)) return true;
        final SBuild build = item.getAssociatedBuild();
        if (build == null || !build.isFinished()) return false; //do not stop while processing queued and running builds
        if (sinceStartDateFinal != null && sinceStartDateFinal.after(build.getStartDate())) return true;
        if (sinceBuildIdFinal != null) {
          if (sinceBuildIdFinal.equals(getBuildId(item))) return true; //found exactly the limiting build - stop here
          if (sinceBuildIdFinal > getBuildId(item)) {
            currentLookAheadCount++;
          } else {
            currentLookAheadCount = 0; //reset the counter
          }
          return currentLookAheadCount > lookAheadCount; // stop only after finding more than lookAheadCount builds with lesser id (try to take into account builds reordering)
        }
        return false;
      }
    };
  }

  @NotNull
  private Locator getStateLocator(@NotNull final Locator locator) {
    final String stateDimension = locator.getSingleDimensionValue(STATE);
    if (stateDimension != null) {
      return createStateLocator(stateDimension);
    }

    final String stateRunningDimension = locator.getSingleDimensionValue(RUNNING); //compatibility with pre-9.1
    if (stateRunningDimension != null) {
      final Boolean legacyRunning = locator.getSingleDimensionValueAsBoolean(RUNNING);
      if (legacyRunning == null) {
        return createStateLocator(Locator.getStringLocator(STATE_FINISHED, "true", STATE_RUNNING, "true", STATE_QUEUED, "false"));
      }
      if (legacyRunning) {
        return createStateLocator(STATE_RUNNING);
      } else {
        return createStateLocator(STATE_FINISHED);
      }
    }

    return createStateLocator(STATE_ANY); // default to all the builds
  }

  @NotNull
  private MultiCheckerFilter<SBuild> getBuildFilter(@NotNull final Locator locator) {
    final MultiCheckerFilter<SBuild> result = new MultiCheckerFilter<>();

    final String buildNumber = locator.getSingleDimensionValue(NUMBER);
    if (buildNumber != null) {
      result.add(item -> buildNumber.equals(item.getBuildNumber()));
    }

    final String status = locator.getSingleDimensionValue(STATUS);
    if (status != null) {
      result.add(item -> status.equalsIgnoreCase(item.getStatusDescriptor().getStatus().getText()));
    }

    final Boolean pinned = locator.getSingleDimensionValueAsBoolean(PINNED);
    if (pinned != null) {
      result.add(item -> FilterUtil.isIncludedByBooleanFilter(pinned, item.isPinned()));
    }

    final Boolean hanging = locator.getSingleDimensionValueAsBoolean(HANGING);
    if (hanging != null) {
      result.add(item -> {
        if (item.isFinished()) return !hanging;
        return FilterUtil.isIncludedByBooleanFilter(hanging, ((SRunningBuild)item).isProbablyHanging());
      });
    }

    if (locator.isUnused(AGENT_NAME)) {
      final String agentName = locator.getSingleDimensionValue(AGENT_NAME);
      if (agentName != null) {
        final ValueCondition agentNameCondition = ParameterCondition.createValueCondition(agentName);
        result.add(item -> agentNameCondition.matches(item.getAgentName()));
      }
    }

    if (locator.isUnused(AGENT_TYPE_ID)) {
      final Long agentTypeId = locator.getSingleDimensionValueAsLong(AGENT_TYPE_ID);
      if (agentTypeId != null) {
        result.add(item -> agentTypeId.intValue() == item.getAgent().getAgentTypeId());
      }
    }

    final Date sinceDate = DataProvider.parseDate(locator.getSingleDimensionValue(SINCE_DATE)); //see also settings cut off date in main filter
    if (sinceDate != null) {
      result.add(item -> sinceDate.before(item.getStartDate()));
    }

    final Date untilDate = DataProvider.parseDate(locator.getSingleDimensionValue(UNTIL_DATE));
    if (untilDate != null) {
      result.add(item -> !(untilDate.before(item.getStartDate())));
    }

    final List<String> statisticValues = locator.getDimensionValue(STATISTIC_VALUE);
    if (!statisticValues.isEmpty()) {
      final Matcher<ParametersProvider> parameterCondition = ParameterCondition.create(statisticValues);
      result.add(item -> parameterCondition.matches(new AbstractMapParametersProvider(Build.getBuildStatisticsValues(item))));
    }

    if (locator.isUnused(TEST_OCCURRENCE)) {
      final String testOccurrence = locator.getSingleDimensionValue(TEST_OCCURRENCE);
      if (testOccurrence != null) {
        TestOccurrenceFinder testOccurrenceFinder = myServiceLocator.getSingletonService(TestOccurrenceFinder.class);
        Set<Long> buildPromotionIds =
          testOccurrenceFinder.getItems(testOccurrence).myEntries.stream().map(sTestRun -> sTestRun.getBuild().getBuildPromotion().getId()).collect(Collectors.toSet());
        result.add(item -> buildPromotionIds.contains(item.getBuildPromotion().getId()));
      }
    }

    final String test = locator.getSingleDimensionValue(TEST);
    if (test != null) {
      TestFinder testFinder = myServiceLocator.getSingletonService(TestFinder.class);
      result.add(item -> {
        String locator1 = new Locator(test).setDimension(TestFinder.BUILD, getLocator(item.getBuildPromotion())).setDimension(PagerData.COUNT, "1").getStringRepresentation();
        return !testFinder.getItems(locator1).myEntries.isEmpty();
      });
    }

    return result;
  }

  private static void ensureCanView(@NotNull final BuildPromotion buildPromotion) {
    //checking permissions to view - workaround for TW-45544
    try {
      buildPromotion.getBuildType();
    } catch (AccessDeniedException e) {
      //concealing the message which contains build configuration id: You do not have enough permissions to access build type with id: XXX
      String message = "Not enough permissions to access build with id: " + buildPromotion.getId();
      LOG.debug(message, e);
      throw new AccessDeniedException(e.getAuthorityHolder(), message);
    }
  }

  @NotNull
  private Long getBuildId(@Nullable final String buildLocator) {
    //the logic should match that of findSingleItem
    if (buildLocator == null) {
      throw new BadRequestException("Cannot find build or build id for empty locator. Try specifying '" + DIMENSION_ID + "' locator dimension");
    }

    final Locator locator = new Locator(buildLocator);
    final Long id = locator.getSingleDimensionValueAsLong(DIMENSION_ID);
    if (id != null && locator.getUnusedDimensions().isEmpty()) {
      return id;
    }

    throw new BadRequestException("Cannot find build or build id for locator '" + buildLocator + "'. Try specifying '" + DIMENSION_ID + "' locator dimension");
  }

  @NotNull
  public static Long getBuildId(@NotNull final BuildPromotion buildPromotion) {
    final Long buildId = buildPromotion.getAssociatedBuildId(); //it is important to get id from the build as that might be different from promotion id
    return buildId != null ? buildId : buildPromotion.getId(); // there should be no queued builds with old ids (TW-38777)
  }

  private boolean isTagsMatchLocator(final List<String> buildTags, final Locator tagsLocator) {
    if (!"extended".equals(tagsLocator.getSingleDimensionValue("format"))) {
      throw new BadRequestException("Only 'extended' value is supported for 'format' dimension of 'tag' dimension");
    }
    final Boolean present = tagsLocator.getSingleDimensionValueAsBoolean("present", true);
    final String patternString = tagsLocator.getSingleDimensionValue("regexp");
    if (present == null) {
      return true;
    }
    Boolean tagsMatchPattern = null;
    if (patternString != null) {
      if (StringUtil.isEmpty(patternString)) {
        throw new BadRequestException("'regexp' sub-dimension should not be empty for 'tag' dimension");
      }
      try {
        tagsMatchPattern = tagsMatchPattern(buildTags, patternString);
      } catch (PatternSyntaxException e) {
        throw new BadRequestException(
          "Bad syntax for Java regular expression in 'regexp' sub-dimension of 'tag' dimension: " + e.getMessage(), e);
      }
    }
    if (tagsMatchPattern == null) {
      if ((present && buildTags.size() != 0) || (!present && (buildTags.size() == 0))) {
        return true;
      }
    } else {
      if (present && tagsMatchPattern) {
        return true;
      } else if (!present && !tagsMatchPattern) {
        return true;
      }
    }
    return false;
  }

  private Boolean tagsMatchPattern(@NotNull final List<String> tags, @NotNull final String patternString) throws PatternSyntaxException {
    final Pattern pattern = Pattern.compile(patternString);
    boolean atLestOneMatches = false;
    for (String tag : tags) {
      atLestOneMatches = atLestOneMatches || pattern.matcher(tag).matches();
    }
    return atLestOneMatches;
  }


  @NotNull
  @Override
  public ItemHolder<BuildPromotion> getPrefilteredItems(@NotNull Locator locator) {

    if (!myPermissionChecker.hasPermissionInAnyProject(Permission.VIEW_PROJECT)){
      return ItemHolder.of(Collections.emptyList()); //TeamCity issue: should be handled in core. Do not spend any resources on user without permissions
    }

    final Boolean byPromotion = locator.getSingleDimensionValueAsBoolean(BY_PROMOTION);
    if (byPromotion != null && !byPromotion) {
      throw new BadRequestException("Found '" + BY_PROMOTION + "' locator set to 'false' which is not supported");
    }

    final String strob = locator.getSingleDimensionValue(STROB);
    if (strob != null) {
      final Locator strobLocator = new Locator(strob, BUILD_TYPE, BRANCH, STROB_BUILD_LOCATOR);

      List<Locator> partialLocators = Collections.singletonList(Locator.createEmptyLocator());
      partialLocators = getPartialLocatorsForStrobDimension(partialLocators, strobLocator, BUILD_TYPE, myBuildTypeFinder);
      partialLocators = getPartialLocatorsForStrobDimension(partialLocators, strobLocator, BRANCH, myBranchFinder);

      final String strobBuildLocator = strobLocator.getSingleDimensionValue(STROB_BUILD_LOCATOR);

      final List<ItemHolder<BuildPromotion>> strobResult = new ArrayList<>();
      for (Locator partialLocator : partialLocators) {
        partialLocator.setDimensionIfNotPresent(PagerData.COUNT, "1");  //limit to single item per strob item by default
        final String finalBuildLocator = Locator.createLocator(strobBuildLocator, partialLocator, new String[]{}).getStringRepresentation();
        strobResult.add(ItemHolder.of(getItems(finalBuildLocator).myEntries));
      }
      strobLocator.checkLocatorFullyProcessed();
      return ItemHolder.concat(strobResult);
    }

    setLocatorDefaults(locator);

    final String equivalent = locator.getSingleDimensionValue(EQUIVALENT);
    if (equivalent != null) {
      final BuildPromotionEx build = (BuildPromotionEx)getItem(equivalent);
      final List<BuildPromotionEx> result = build.getStartedEquivalentPromotions(-1);
      final Set<BuildPromotion> convertedResult = new TreeSet<BuildPromotion>(BUILD_PROMOTIONS_COMPARATOR);
      convertedResult.addAll(result);
      return ItemHolder.of(convertedResult);
    }

    final String metadata = locator.getSingleDimensionValue(METADATA);
    if (metadata != null) {
      Iterable<BuildMetadataEntry> metadataEntries = () -> getBuildMetadataEntryIterator(metadata);
      return processor -> {
        for (BuildMetadataEntry metadataEntry : metadataEntries) {
          SBuild build = myBuildsManager.findBuildInstanceById(metadataEntry.getBuildId());
          if (build != null && Build.canViewRuntimeData(myPermissionChecker, build.getBuildPromotion())) {
            processor.processItem(build.getBuildPromotion());
          }
        }
      };
    }

    final String graphLocator = locator.getSingleDimensionValue(ORDERED);
    if (graphLocator != null) {
      final GraphFinder<BuildPromotion> graphFinder = new BuildPromotionOrderedFinder(this);
      //consider performance optimization by converting id to build only on actual retrieve (use GraphFinder<Int/buildId>)
      return ItemHolder.of(graphFinder.getItems(graphLocator).myEntries);
    }

    final String snapshotDepDimension = locator.getSingleDimensionValue(SNAPSHOT_DEP);
    if (snapshotDepDimension != null) {
      return ItemHolder.of(getSnapshotRelatedBuilds(snapshotDepDimension));
    }

    final String artifactDepDimension = locator.getSingleDimensionValue(ARTIFACT_DEP);
    if (artifactDepDimension != null) {
      return ItemHolder.of(getArtifactRelatedBuilds(artifactDepDimension, locator));
    }

    final String snapshotDepProblem = locator.getSingleDimensionValue(SNAPSHOT_PROBLEM);
    if (snapshotDepProblem != null) {
      return ItemHolder.of(getSnapshotDepProblemBuilds(snapshotDepProblem));
    }

    final String number = locator.getSingleDimensionValue(NUMBER);
    if (number != null) {
      final String buildTypeLocator = locator.getSingleDimensionValue(BUILD_TYPE);
      if (buildTypeLocator != null) {
        final List<SBuildType> buildTypes = myBuildTypeFinder.getBuildTypes(null, buildTypeLocator);
        final Set<BuildPromotion> builds = new TreeSet<>(BUILD_PROMOTIONS_COMPARATOR);
        for (SBuildType buildType : buildTypes) {
          List<SBuild> buildByNumber = myBuildsManager.findBuildInstancesByBuildNumber(buildType.getBuildTypeId(), number);
          builds.addAll(CollectionsUtil.convertCollection(buildByNumber, SBuild::getBuildPromotion)); //todo: ensure due builds sorting
        }
        return ItemHolder.of(builds);
      } else {
        // if build type is not specified, search by scanning (performance impact)
        locator.markUnused(NUMBER, BUILD_TYPE);
      }

      /*
        Search by build number and project id
       */
      if (TeamCityProperties.getBooleanOrTrue("rest.builds.selectByProjectAndBuildNumberOptimization.enabled")) {
        if (locator.isUnused(PROJECT)) {
          SProject project = getProjectFromDimension(locator, PROJECT);
          if (project != null) {
            List<SBuildType> buildTypes = project.getOwnBuildTypes();
            return getBuildsByBuildTypesAndBuildNumber(buildTypes, number);
          }
        }
        if (locator.isUnused(AFFECTED_PROJECT)) {
          SProject project = getProjectFromDimension(locator, AFFECTED_PROJECT);
          if (project != null) {
            List<SBuildType> buildTypes = project.getBuildTypes();
            return getBuildsByBuildTypesAndBuildNumber(buildTypes, number);
          }
        }
      }
    }

    if (locator.isAnyPresent(TAG) && TeamCityProperties.getBooleanOrTrue("rest.request.builds.prefilterByTag")) {
      Locator stateLocator = getStateLocator(new Locator(locator)); //using locator copy so that no dimensions are marked as used
      if (isStateIncluded(stateLocator, STATE_FINISHED)) {//no sense in going further here if no finished builds are requested
        final List<String> tagLocators = locator.lookupDimensionValue(TAG); //not marking as used to enforce filter processing later

        Stream<BuildPromotion> finishedBuilds = TagFinder.getPrefilteredFinishedBuildPromotions(tagLocators, myServiceLocator);
        if (finishedBuilds != null) {
          FilterConditionChecker<BuildPromotion> tagsFilter = getFilterByTag(tagLocators);
          // After this point no other builds will be added
          locator.markUsed(TAG);

          Stream<BuildPromotion> queuedBuilds = Stream.empty();
          if(isStateIncluded(stateLocator, STATE_QUEUED)) {
             queuedBuilds = myBuildQueue.getItems().stream()
                                        .map(SQueuedBuild::getBuildPromotion);
          }

          Stream<BuildPromotion> runningBuilds = Stream.empty();
          if(isStateIncluded(stateLocator, STATE_RUNNING)) {
            // BuildsManager.processBuilds could be used here instead of getting finished and running builds separately, but BuildQueryOptions support
            // only exact tag name match, so we can't satisfy all possible TAG queriy conditions with it. To counter that we use
            // TagFinder.getPrefilteredFinishedBuildPromotions to obtain a subset of required finished builds and BuildsManager.getRunningBuilds()
            // to get all runing builds and later filter everything properly.
            runningBuilds = myBuildsManager.getRunningBuilds().stream()
                                           .map(SRunningBuild::getBuildPromotion);
          }

          // None of the concatenated streams were properly filtered, so let's filter them now.
          // This hopefully allows for avoiding of hitting a lookupLimit because of a large queue.
          Stream<BuildPromotion> allBuildsFilteredByTag = Stream.concat(Stream.concat(queuedBuilds, runningBuilds), finishedBuilds)
                                                                .filter(tagsFilter::isIncluded);

          return processor -> allBuildsFilteredByTag.forEach(processor::processItem);
        }
      }
    }

    final String testOccurrence = locator.getSingleDimensionValue(TEST_OCCURRENCE);
    if (testOccurrence != null) {
      TestOccurrenceFinder testOccurrenceFinder = myServiceLocator.getSingletonService(TestOccurrenceFinder.class);
      return ItemHolder.of(testOccurrenceFinder.getItems(testOccurrence).myEntries.stream().map(sTestRun -> sTestRun.getBuild().getBuildPromotion()));
    }

    SBuildAgent agent;
    final String agentLocator = locator.getSingleDimensionValue(AGENT);
    if (agentLocator != null) {
      List<SBuildAgent> agents = myAgentFinder.getItemsNotEmpty(agentLocator).myEntries;
      if (agents.size() == 1) {
        agent = agents.get(0);
      } else {  //only if builds processor cannot handle this
        Stream<BuildPromotion> result = Stream.empty();

        Locator stateLocator = getStateLocator(locator);
        if (isStateIncluded(stateLocator, STATE_QUEUED)) {
          //todo: should sort backwards as currently the order does not seem right...
          result =
            Stream.concat(result, myBuildQueue.getItems().stream().filter(build -> !CollectionsUtil.intersect(build.getCanRunOnAgents(), agents).isEmpty())
                                              .map(build -> build.getBuildPromotion()));
        }

        if (isStateIncluded(stateLocator, STATE_RUNNING)) {  //todo: address an issue when a build can appear twice in the output
          // agent instance can be different when disconnecting, so need to check id
          Set<Integer> agentIds = agents.stream().map(a -> a.getId()).collect(Collectors.toSet());
          Set<String> agentNames = agents.stream().map(a -> a.getName()).collect(Collectors.toSet());
          result = Stream.concat(result, myBuildsManager.getRunningBuilds().stream().filter(build -> {
            SBuildAgent buildAgent = build.getAgent();
            int agentId = buildAgent.getId();
            return agentId > 0 ? agentIds.contains(agentId) : agentNames.contains(buildAgent.getName());
          }).map(build -> build.getBuildPromotion()));
        }

        if (isStateIncluded(stateLocator, STATE_FINISHED)) {
          //todo: optimize for user and canceled
          Stream<BuildPromotion> finishedBuilds = StreamUtil.merge(
            agents.stream().map(a -> a.getBuildHistory(null, true).stream().map(b -> b.getBuildPromotion())), BUILD_PROMOTIONS_COMPARATOR);
          result = Stream.concat(result, finishedBuilds);
        }
        return ItemHolder.of(result);
      }
    } else {
      agent = null;
    }

    // process by build states

    final List<BuildPromotion> result = new ArrayList<>();
    Set<Long> includedPromotionIds = new HashSet<>();
    @Nullable Set<SBuildType> buildTypes = getBuildTypes(locator);

    String agentName;
    String agentNameCondition = locator.lookupSingleDimensionValue(AGENT_NAME);
    if (agentNameCondition != null) {
      agentName = ParameterCondition.createValueCondition(agentNameCondition).getConstantValueIfSimpleEqualsCondition();
      if (agentName != null) locator.markUsed(AGENT_NAME);
    } else {
      agentName = null;
    }

    Long agentTypeId = locator.getSingleDimensionValueAsLong(AGENT_TYPE_ID);
    Locator stateLocator = getStateLocator(locator);

    IncludePersonalBuildsRuling personalBuildsRuling = computePersonalBuildsRuling(locator);

    if (isStateIncluded(stateLocator, STATE_QUEUED)) {
      //todo: should sort backwards as currently the order does not seem right...
      Stream<SQueuedBuild> builds = myBuildQueue.getItems().stream();
      if(!personalBuildsRuling.isIncludePersonal()) {
        builds = builds.filter(build -> !build.isPersonal());
      } else {
        if(personalBuildsRuling.getOwner() != null) {
          builds = builds.filter(build -> !build.isPersonal() || personalBuildsRuling.getOwner().equals(build.getBuildPromotion().getOwner()));
        }
      }

      if (buildTypes != null) { //make sure buildTypes retrieved from the locator are used
        builds = builds.filter(qb -> buildTypes.contains(qb.getBuildPromotion().getParentBuildType()));
      }
      if (agentTypeId != null) {
        builds = builds.filter(build -> build.getCanRunOnAgents().stream().anyMatch(a -> a.getAgentTypeId() == agentTypeId.intValue()));
      }
      if (agent != null) {
        builds = builds.filter(build -> build.getCanRunOnAgents().stream().anyMatch(a -> a.equals(agent))); //todo: check
      }
      if (agentName != null) {
        builds = builds.filter(build -> build.getCanRunOnAgents().stream().anyMatch(a -> a.getName().equals(agentName)));
      }

      builds.map(b -> b.getBuildPromotion()).forEach(p -> {
        if (includedPromotionIds.add(p.getId())) {
          result.add(p);
        }
      });
    }

    if (isStateIncluded(stateLocator, STATE_RUNNING)) {
      Stream<SRunningBuild> builds;
      if(personalBuildsRuling.isIncludePersonal()) {
        builds = myBuildsManager.getRunningBuilds(personalBuildsRuling.myOwner, null).stream();
      } else {
        builds = myBuildsManager.getRunningBuilds().stream()
                                .filter(build -> !build.isPersonal());
      }

      if (buildTypes != null) { //make sure buildTypes retrieved from the locator are used
        builds = builds.filter(b -> buildTypes.contains(b.getBuildPromotion().getParentBuildType()));
      }

      if (agentTypeId != null) {
        builds = builds.filter(build -> build.getAgent().getAgentTypeId() == agentTypeId.intValue());
      }
      if (agent != null) {
        builds = builds.filter(build -> {
          SBuildAgent buildAgent = build.getAgent();
          int agentId = buildAgent.getId();
          return agentId > 0 ? agent.getId() == agentId : agent.getName().equals(buildAgent.getName());
        });
      }
      if (agentName != null) {
        builds = builds.filter(build -> build.getAgentName().equals(agentName));
      }

      builds.map(b -> b.getBuildPromotion()).forEach(p -> {
        if (includedPromotionIds.add(p.getId())) {
          result.add(p);
        }
      });
    }

    ItemHolder<BuildPromotion> finishedBuilds = null;
    if (isStateIncluded(stateLocator, STATE_FINISHED)) {
      final BuildQueryOptions options = new BuildQueryOptions();
      if (buildTypes != null) {
        options.setBuildTypeIds(buildTypes.stream().map(bt -> bt.getBuildTypeId()).collect(Collectors.toList()));
      }

      if (agentTypeId != null) {
        options.setAgentTypeId(agentTypeId.intValue());
      }
      if (agent != null) {
        options.setAgent(agent);
      }
      if (agentName != null) {
        options.setAgentName(agentName);
      }

      final Boolean failedToStart = locator.lookupSingleDimensionValueAsBoolean(FAILED_TO_START);
      final Boolean canceled = locator.lookupSingleDimensionValueAsBoolean(CANCELED);
      if (canceled == null || canceled || failedToStart == null || failedToStart) {
        options.setIncludeCanceled(true); //also includes failed to start builds, TW-32060
      } else {
        options.setIncludeCanceled(false);
      }

      final Boolean pinned = locator.getSingleDimensionValueAsBoolean(PINNED);
      if (pinned != null) {
        options.setPinStatus(pinned);
      }

      TagFinder.FilterOptions tagFilterOptions = TagFinder.getFilterOptions(locator.lookupDimensionValue(TAG), myServiceLocator);
      if (tagFilterOptions != null) {
        options.setTagName(tagFilterOptions.getTagName(), tagFilterOptions.getTagOwner());
      }

      final String branchLocatorValue = locator.lookupSingleDimensionValue(BRANCH); // do not mark dimension as used as not all can be used from it
      if (branchLocatorValue != null) {
        BranchFinder.BranchFilterDetails branchFilterDetails;
        try {
          branchFilterDetails = myBranchFinder.getBranchFilterDetailsWithoutLocatorCheck(branchLocatorValue);
          // parsed OK, setting options
          if (branchFilterDetails.isAnyBranch()) {
            options.setMatchAllBranches(true);
          } else {
            if (branchFilterDetails.isDefaultBranchOrNotBranched()) {
              options.setMatchAllBranches(false);
              options.setBranch(Branch.DEFAULT_BRANCH_NAME);
            }
            if (branchFilterDetails.getBranchName() != null) {
              options.setMatchAllBranches(false); //causes a bug in certain cases, see https://youtrack.jetbrains.com/issue/TW-61530 however, no performant fix is possible so far
              options.setBranch(branchFilterDetails.getBranchName());
            }
          }
        } catch (LocatorProcessException e) {
          // not parsed, cannot extract name or default status
          options.setMatchAllBranches(true);
        }
      } else {
        options.setMatchAllBranches(true);
      }

      options.setIncludeRunning(false); //running builds are retrieved separately and appear before finished ones
      options.setOrderByChanges(false);

      // Do not mark COUNT dimension used as we can recieve more results than we need.
      Long count = locator.lookupSingleDimensionValueAsLong(PagerData.COUNT, getDefaultPageItemsCount());
      if (count != null && count != -1) {
        options.setPageSize(count.intValue());
      }

      // In a case when we need personal builds there is a twist, hence the following check.
      // BuildQueryOptions and underlayoing mechanics treats given user as if we are acting at his will.
      // This means that his it will check if the given user can see
      // a specific personal build according to his own permissions and user settings, which is not what we want.
      // Instead, we pretend that we need all personal builds and filter out unwanted ones later.
      final SUser currentUser = (SUser) myServiceLocator.getSingletonService(SecurityContext.class).getAuthorityHolder().getAssociatedUser();
      if(Objects.equals(personalBuildsRuling.myOwner, currentUser)) {
        options.setIncludePersonal(personalBuildsRuling.isIncludePersonal(), personalBuildsRuling.getOwner());
      } else {
        options.setIncludePersonal(personalBuildsRuling.isIncludePersonal(), null);
      }

      finishedBuilds = processor -> myBuildsManager.processBuilds(options, item -> {
        if (includedPromotionIds.contains(item.getBuildPromotion().getId())) return true; // ignore already added builds
        return processor.processItem(item.getBuildPromotion());
      });
    }

    stateLocator.checkLocatorFullyProcessed();

    final ItemHolder<BuildPromotion> finishedBuildsFinal = finishedBuilds;
    return processor -> {
      ItemHolder.of(result).process(processor);
      if (finishedBuildsFinal != null) {
        finishedBuildsFinal.process(processor);
      }
    };
  }

  @NotNull
  private ItemHolder<BuildPromotion> getBuildsByBuildTypesAndBuildNumber(@NotNull List<SBuildType> buildTypes, @NotNull String buildNumber) {
    return ItemHolder.of(() -> buildTypes
      .stream()
      .flatMap(it -> myBuildsManager.findBuildInstancesByBuildNumber(it.getBuildTypeId(), buildNumber).stream())
      .map(it -> it.getBuildPromotion())
      .sorted(BUILD_PROMOTIONS_COMPARATOR)
      .iterator()
    );
  }

  // Package-private for tests
  @NotNull
  IncludePersonalBuildsRuling computePersonalBuildsRuling(@NotNull Locator locator) {
    final Boolean personal = locator.lookupSingleDimensionValueAsBoolean(PERSONAL);
    final SUser currentUser = (SUser)myServiceLocator.getSingletonService(SecurityContext.class).getAuthorityHolder().getAssociatedUser();
    final boolean showAllPersonalBuildsPreferense = currentUser != null && Boolean.parseBoolean(currentUser.getPropertyValue(StandardProperties.SHOW_ALL_PERSONAL_BUILDS));

    if (personal == null || personal) {
      final String userDimension = locator.lookupSingleDimensionValue(USER);
      if (userDimension == null) {
        if (currentUser != null) {
          return new IncludePersonalBuildsRuling(true, showAllPersonalBuildsPreferense ? null : currentUser);
        } else {
          return new IncludePersonalBuildsRuling(true, null);
        }
      } else {
        return new IncludePersonalBuildsRuling(true, myUserFinder.getItem(userDimension));
      }
    } else {
      return new IncludePersonalBuildsRuling(false, null);
    }
  }

  // Package-private for tests
  class IncludePersonalBuildsRuling {
    private final boolean myIncludePersonal;
    private final SUser myOwner;

    public IncludePersonalBuildsRuling(boolean includePersonalBuilds, @Nullable SUser owner) {
      myIncludePersonal = includePersonalBuilds;
      myOwner = owner;
    }

    public boolean isIncludePersonal() {
      return myIncludePersonal;
    }

    @Nullable
    public SUser getOwner() {
      return myOwner;
    }

    @Override
    public String toString() {
      if(!myIncludePersonal) {
        return "Do not include any personal builds.";
      }

      if(myOwner == null) {
        return "Include personal builds of *all* users.";
      }
      return "Only include personal builds of user=" + myOwner.getUsername();
    }
  }

  private HashSet<SBuildType> getBuildTypes(final @NotNull Locator locator) {
    SProject project = getProjectFromDimension(locator, PROJECT);

    final String buildTypeLocator = locator.getSingleDimensionValue(BUILD_TYPE);
    if (buildTypeLocator != null) {
      List<SBuildType> result = myBuildTypeFinder.getBuildTypes(project, buildTypeLocator);
      if (result.isEmpty()) {
        throw new NotFoundException("No build types found by locator '" + buildTypeLocator + "'" +
                                    (project != null ? " in the project " + project.describe(false) : ""));
      }
      return new HashSet<>(result);
    }

    if (project != null) {
      List<SBuildType> result = project.getOwnBuildTypes();
      if (result.isEmpty()) {
        throw new NotFoundException("No build types found in the project " + project.describe(false));
      }
      return new HashSet<>(result);
    }

    SProject affectedProject = getProjectFromDimension(locator, AFFECTED_PROJECT);
    if (affectedProject != null && !affectedProject.isRootProject()) {
      List<SBuildType> result = affectedProject.getBuildTypes();
      if (result.isEmpty()) {
        throw new NotFoundException("No build types found under the affected project " + affectedProject.describe(false));
      }
      return new HashSet<>(result);
    }

    return null;
  }

  @NotNull
  private Iterator<BuildMetadataEntry> getBuildMetadataEntryIterator(@NotNull final String metadataLocatorText) {
    Locator metadataLocator = new Locator(metadataLocatorText, "providerId", "key");
    String providerId = metadataLocator.getSingleDimensionValue("providerId");
    if (providerId == null) {
      throw new BadRequestException("Metadata locator '" + metadataLocatorText + "' should contain '" + "providerId" + "' dimension.");
    }
    String key = metadataLocator.getSingleDimensionValue("key");
    metadataLocator.checkLocatorFullyProcessed();

    return key == null ? myMetadataStorage.getAllEntries(providerId) : myMetadataStorage.getEntriesByKey(providerId, key);
  }

  @NotNull
  private <ITEM> List<Locator> getPartialLocatorsForStrobDimension(@NotNull final List<Locator> resultStrobBuildLocators, @NotNull final Locator strobLocator, @NotNull final String strobDimension,
                                                                   @NotNull final Finder<ITEM> strobTypeFinder) {
    final String strobDimensionValue = strobLocator.getSingleDimensionValue(strobDimension);
    if (strobDimensionValue == null) {
      return resultStrobBuildLocators;
    }
    List<Locator> result = new ArrayList<>();
    for (Locator strobBuildLocator : resultStrobBuildLocators) {

      String patchedStrobDimensionValue = strobDimensionValue;
      // preprocess locator before use
      if (BUILD_TYPE.equals(strobDimension)) {
        patchedStrobDimensionValue = Locator.setDimensionIfNotPresent(strobDimensionValue, BuildTypeFinder.TEMPLATE_FLAG_DIMENSION_NAME, "false");
      } else {
        final String buildTypeLocator = strobBuildLocator.getSingleDimensionValue(BUILD_TYPE);
        if (BRANCH.equals(strobDimension) && buildTypeLocator != null) {
          patchedStrobDimensionValue = Locator.setDimensionIfNotPresent(strobDimensionValue, BranchFinder.BUILD_TYPE, buildTypeLocator);
        }
      }

      final PagedSearchResult<ITEM> items = strobTypeFinder.getItems(patchedStrobDimensionValue);
      for (ITEM item : items.myEntries) {
        result.add(new Locator(strobBuildLocator).setDimensionIfNotPresent(strobDimension, strobTypeFinder.getCanonicalLocator(item)));
      }
    }
    return result;
  }

  private void setLocatorDefaults(@NotNull final Locator locator) {
    if (locator.isSingleValue()) {
      return;
    }
    final Boolean defaultFiltering = locator.getSingleDimensionValueAsBoolean(DEFAULT_FILTERING);
    if (defaultFiltering != null && !defaultFiltering) {
      return;
    }

    //this is added for consistency - currently this never triggers as findSingleItem treats the dimensions without ever calling setLocatorDefaults
    if (defaultFiltering == null && locator.isAnyPresent(DIMENSION_ID, PROMOTION_ID, PROMOTION_ID_ALIAS, BUILD_ID)) {
      return;
    }

    if (!locator.isAnyPresent(STATE, RUNNING)) {
      locator.setDimension(STATE, STATE_FINISHED);
    }

    if (defaultFiltering == null) { // if it is set, then use the value ("true" if we got there)
      if (TeamCityProperties.getBooleanOrTrue("rest.buildPromotionFinder.varyingDefaults")) {
        if (locator.isAnyPresent(AGENT, AGENT_NAME, USER, HANGING, EQUIVALENT, TRIGGERED, SNAPSHOT_PROBLEM)) {
          // users usually expect that any build will be returned for such locators, see TW-45140
          return;
        }
        Locator stateLocator = getStateLocator(new Locator(locator)); //creating locator copy here not to affect used dimensions
        if (!isStateIncluded(stateLocator, STATE_FINISHED)) {
          // also including all builds if the only requested are running or queued builds
          return;
        }
      }
    }

    locator.setDimensionIfNotPresent(PERSONAL, "false");

    locator.setDimensionIfNotPresent(CANCELED, "false");
    locator.setDimensionIfNotPresent(FAILED_TO_START, "false");
    if (defaultFiltering != null || !locator.isAnyPresent(SNAPSHOT_DEP, EQUIVALENT, ORDERED)) {
      //do not force branch to default for some locators
      locator.setDimensionIfNotPresent(BRANCH, BranchFinder.getDefaultBranchLocator());
    }
  }

  @NotNull
  public BuildPromotion getBuildPromotion(final @Nullable SBuildType buildType, @Nullable final String locatorText) {
    if (buildType == null) {
      return getItem(locatorText);
    }

    final Locator locator = locatorText != null ? new Locator(locatorText) : Locator.createEmptyLocator();
    if (locator.isEmpty() || !locator.isSingleValue()) {
      return getItem(patchLocatorWithBuildType(buildType, locator));
    }
    //single value locator
    //use logic like BuildFinder: if there is build type and single value, assume it's build number
    final String buildNumber = locator.getSingleValue();
    assert buildNumber != null;
    SBuild build = myBuildsManager.findBuildInstanceByBuildNumber(buildType.getInternalId(), buildNumber);
    if (build != null) return build.getBuildPromotion();

    throw new NotFoundException("No build can be found by number '" + buildNumber + "' in the build type with id '" + buildType.getExternalId() + "'");

    /*
    final BuildPromotion singleItem = findSingleItem(locator);
    if (singleItem != null) { //will find it the regular way, go for it with all due checks
      return getItem(locator.getStringRepresentation());
    }
    */
  }

  @NotNull
  public BuildPromotion getBuildPromotion(final long promotionId) {
    final BuildPromotion buildPromotion = myBuildPromotionManager.findPromotionOrReplacement(promotionId);
    if (buildPromotion == null) {
      throw new NotFoundException("No build promotion can be found by promotion id " + promotionId);
    }
    ensureCanView(buildPromotion);

    return buildPromotion;
  }

  /**
   * First try to find promotion interpreting {@code id} as promotion id, then in a case of a fail interpret {@code id} as build id.
   * <br/>
   * See also: {@link BuildPromotionFinder#getBuildPromotion(long)}
   * @throws NotFoundException
   * @throws AccessDeniedException
   */
  @NotNull
  public BuildPromotion getBuildPromotionByIdOrByBuildId(@NotNull final Long id) {
    //the logic should match that of getBuildId(String)
    final BuildPromotion buildPromotion = myBuildPromotionManager.findPromotionOrReplacement(id);
    if (buildPromotion != null && (getBuildId(buildPromotion) == buildPromotion.getId())) {
      ensureCanView(buildPromotion);
      return buildPromotion;
    }
    final SBuild build = myBuildsManager.findBuildInstanceById(id);
    if (build != null) {
      return build.getBuildPromotion();
    }
    throw new NotFoundException("No build found by id '" + id + "'.");
  }

  @NotNull
  public PagedSearchResult<BuildPromotion> getBuildPromotions(final @Nullable SBuildType buildType, final @Nullable String locatorText) {
    if (buildType == null) {
      return getItems(locatorText);
    }

    final Locator locator = locatorText != null ? new Locator(locatorText) : Locator.createEmptyLocator();
    if (locator.isEmpty() || !locator.isSingleValue()) {
      return getItems(patchLocatorWithBuildType(buildType, locator));  //todo: test empty locator with not empty build type
    }

    //single value
    return new PagedSearchResult<BuildPromotion>(Collections.singletonList(getBuildPromotion(buildType, locatorText)), null, null);
  }

  @NotNull
  public PagedSearchResult<BuildPromotion> getBuildPromotionsWithLegacyFallback(@Nullable final SBuildType buildType, @Nullable final String locatorText) {
    if (!TeamCityProperties.getBoolean(LEGACY_BUILDS_FILTERING_FORCED)) {
      return getBuildPromotions(buildType, locatorText);
    }

    Locator locator;
    try {
      locator = locatorText != null ? new Locator(locatorText) : Locator.createEmptyLocator();
    } catch (LocatorProcessException e) {
      //error creating locator - some special dimensions used? process by default
      return getBuildPromotions(buildType, locatorText);
    }
    if (useByPromotionFiltering(locator)) {
      return getBuildPromotions(buildType, locatorText);
    }

    BuildFinder legacyFinder = myServiceLocator.getSingletonService(BuildFinder.class);
    return legacyFinder.getBuildsLegacy(buildType, locator);
  }

  private boolean useByPromotionFiltering(@NotNull final Locator locator) {
    final Boolean byPromotion = locator.getSingleDimensionValueAsBoolean(BuildPromotionFinder.BY_PROMOTION, !TeamCityProperties.getBoolean(LEGACY_BUILDS_FILTERING));
    return byPromotion != null && byPromotion;
  }

  @NotNull
  private String patchLocatorWithBuildType(@Nullable final SBuildType buildType, @NotNull final Locator locator) {
    if (buildType != null) {
        return locator.setDimensionIfNotPresent(BuildPromotionFinder.BUILD_TYPE, BuildTypeFinder.getLocator(buildType)).getStringRepresentation();
    }
    return locator.getStringRepresentation();
  }

  @NotNull
  private List<BuildPromotion> getSnapshotRelatedBuilds(@NotNull final String snapshotDepDimension) {
    final GraphFinder<BuildPromotion> graphFinder = new GraphFinder<BuildPromotion>(this, SNAPSHOT_DEPENDENCIES_TRAVERSER);
    final List<BuildPromotion> result = graphFinder.getItems(snapshotDepDimension).myEntries;
    sortBuildPromotions(result);
    return result; //todo: patch branch locator, personal, etc.???
  }

  @NotNull
  private List<BuildPromotion> getArtifactRelatedBuilds(@NotNull final String depDimension, @NotNull final Locator locator) {
    final GraphFinder<BuildPromotion> graphFinder = new GraphFinder<BuildPromotion>(this, new ArtifactDepsTraverser(locator));
    final List<BuildPromotion> result = graphFinder.getItems(depDimension).myEntries;
    sortBuildPromotions(result);
    return result; //todo: patch branch locator, personal, etc.???
  }

  @NotNull
  private List<BuildPromotion> getSnapshotDepProblemBuilds(@NotNull final String locator) {
    final GraphFinder<BuildPromotion> graphFinder = new GraphFinder<BuildPromotion>(this, mySnapshotDepProblemsTraverser);
    final List<BuildPromotion> result = graphFinder.getItems(locator).myEntries;
    sortBuildPromotions(result);
    return result;
  }

  private void sortBuildPromotions(@NotNull final List<BuildPromotion> result) {
    if (result.size() == 1) {
      return;
    }

    BuildPromotionComparator.sort(result, myBuildsManager);
  }

  @NotNull
  private Locator createStateLocator(@NotNull final String stateDimension) {
    final Locator locator = new Locator(stateDimension, Locator.LOCATOR_SINGLE_VALUE_UNUSED_NAME, STATE_QUEUED, STATE_RUNNING, STATE_FINISHED);
    if (locator.isSingleValue()) {
      //check single value validity
      if (!stateDimension.equals(STATE_QUEUED) &&
          !stateDimension.equals(STATE_RUNNING) &&
          !stateDimension.equals(STATE_FINISHED) &&
          !stateDimension.equals(STATE_ANY)) {
        throw new BadRequestException("Unsupported value of '" + STATE + "' dimension: '" + stateDimension +
                                      "'. Should be one of the build states('" + STATE_QUEUED + "', '" + STATE_RUNNING + "', '" + STATE_FINISHED + "') or '" + STATE_ANY + "'");
      }
    }

    return locator;
  }

  private boolean isStateIncluded(@NotNull final Locator stateLocator, @NotNull final String state) {
    final String singleValue = stateLocator.getSingleValue();
    if (singleValue != null && (STATE_ANY.equals(singleValue) || state.equals(singleValue))) {
      return true;
    }
    //noinspection RedundantIfStatement
    if (!stateLocator.isSingleValue() && FilterUtil.isIncludedByBooleanFilter(stateLocator.getSingleDimensionValueAsBoolean(state, false), true)) {
      return true;
    }
    return false;
  }

  public static boolean buildIdDiffersFromPromotionId(@NotNull final BuildPromotion buildPromotion) {
    return buildPromotion.getAssociatedBuildId() != null && buildPromotion.getId() != buildPromotion.getAssociatedBuildId();
  }

  private static class BuildPromotionComparator implements Comparator<BuildPromotion> {
    private final Map<Long, SBuild> myResolvedBuildsMap;

    public BuildPromotionComparator() {
      myResolvedBuildsMap = null;
    }

    /**
     * @param resolvedBuildsMap map of (promotion id -> build) for all the builds which are going to be compared
     */
    private BuildPromotionComparator(@NotNull Map<Long, SBuild> resolvedBuildsMap) {
      myResolvedBuildsMap = resolvedBuildsMap;
    }

    public static void sort(@NotNull final List<BuildPromotion> builds, @NotNull final BuildsManager buildsManager) {
      Set<Long> buildIds = builds.stream().map(BuildPromotion::getAssociatedBuildId).filter(Objects::nonNull).collect(Collectors.toSet());

      Map<Long, SBuild> resolvedBuildsMap = new HashMap<>();
      if (!buildIds.isEmpty()) {
        for (SBuild build: buildsManager.findBuildInstances(buildIds)) {
          resolvedBuildsMap.put(build.getBuildPromotion().getId(), build);
        }
      }

      Collections.sort(builds, new BuildPromotionComparator(resolvedBuildsMap));
    }

    public int compare(final BuildPromotion o1, final BuildPromotion o2) {
      final SQueuedBuild qb1 = o1.getQueuedBuild();
      final SQueuedBuild qb2 = o2.getQueuedBuild();
      if (qb1 != null) {
        if (qb2 != null) {
          return -qb1.getItemId().compareTo(qb2.getItemId());
        }
        return -1;
      }
      if (qb2 != null) {
        return 1;
      }

      final SBuild b1 = myResolvedBuildsMap == null ? o1.getAssociatedBuild() : myResolvedBuildsMap.get(o1.getId());
      final SBuild b2 = myResolvedBuildsMap == null ? o2.getAssociatedBuild() : myResolvedBuildsMap.get(o2.getId());
      if (b1 != null) {
        if (b2 != null) {
          if (b1.isFinished()) {
            if (b2.isFinished()) {
              final int resultByStartDate = b1.getStartDate().compareTo(b2.getStartDate());
              if (resultByStartDate == 0) return -Long.valueOf(b1.getBuildId()).compareTo(b2.getBuildId());
              return -resultByStartDate;
            }
            return 1;
          }
          if (b2.isFinished()) {
            return -1;
          }
          final int resultByStartDate = b1.getStartDate().compareTo(b2.getStartDate());
          if (resultByStartDate == 0) return -Long.valueOf(b1.getBuildId()).compareTo(b2.getBuildId());
          return -resultByStartDate;
        }
        return 1;
      }
      return -1;
    }
  }

  private static class SnapshotDepsTraverser implements GraphFinder.Traverser<BuildPromotion> {
    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getChildren() {
      return item -> CollectionsUtil.convertCollection(item.getDependencies(), source -> source.getDependOn());
    }

    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getParents() {
      return item -> CollectionsUtil.convertCollection(item.getDependedOnMe(), source -> source.getDependent());
    }
  }

  private static class SnapshotDepProblemsTraverser implements GraphFinder.Traverser<BuildPromotion> {
    @NotNull private final BuildPromotionFinder myBuildPromotionFinder;

    public SnapshotDepProblemsTraverser(@NotNull final BuildPromotionFinder buildPromotionFinder) {
      myBuildPromotionFinder = buildPromotionFinder;
    }

    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getChildren() {
      return parentPromotion -> getFailedDepsIdsStream(parentPromotion).map(SnapshotDepProblemsTraverser::getLong)
                                                                       .filter(Objects::nonNull)
                                                                       .map(myBuildPromotionFinder::getBuildPromotion)
                                                                       .collect(Collectors.toList());
    }

    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getParents() {
      return item -> {
        String id = String.valueOf(item.getId());
        return item.getDependedOnMe().stream().map(BuildDependency::getDependent).filter(bp -> getFailedDepsIdsStream(bp).anyMatch(id::equals)).collect(Collectors.toList());
      };
    }

    @NotNull
    private Stream<String> getFailedDepsIdsStream(@NotNull final BuildPromotion item) {
      return ((BuildPromotionEx)item).getBuildProblems().stream().filter(bp -> ErrorData.isSnapshotDependencyError(bp.getBuildProblemData().getType()) && !bp.isMutedInBuild())
                 .map(bp -> bp.getBuildProblemData().getAdditionalData());
    }

    @Nullable
    private static Long getLong(@Nullable String text) {
      if (StringUtil.isEmpty(text)) return null;
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  private class ArtifactDepsTraverser implements GraphFinder.Traverser<BuildPromotion> {
    @Nullable private final Locator myStateLocator;

    public ArtifactDepsTraverser(@Nullable final Locator locator) {
      if (locator != null) {
        Locator locatorCopy = new Locator(locator); // create a copy in order not to mark the dimensions as used
        myStateLocator = getStateLocator(locatorCopy);
      } else {
        myStateLocator = null;
      }
    }

    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getChildren() {
      return item -> {
          //see also jetbrains.buildServer.server.rest.model.build.Build.getBuildArtifactDependencies
          SBuild build = item.getAssociatedBuild();
          if (build == null)
            return Collections.emptyList();
          return build.getDownloadedArtifacts().getArtifacts().keySet().stream().map(v -> ((SBuild)v).getBuildPromotion())
                      .sorted(new Build.BuildPromotionDependenciesComparator()).collect(Collectors.toList());
      };
    }

    @NotNull
    public GraphFinder.LinkRetriever<BuildPromotion> getParents() {
      return item -> {
          SBuild build = item.getAssociatedBuild();
          if (build == null)
            return Collections.emptyList();
          //getArtifactRelatedBuildsCheapCount should be consistent with this logic, so far it does not support non-finished builds
          Stream<BuildPromotion> result = Stream.empty();
          //myStateLocator is null if locator was not passed. Defaulting to finished builds only then
          if (myStateLocator != null && isStateIncluded(myStateLocator, STATE_QUEUED)) {
            result = Stream.concat(result, myBuildQueue.getItems().stream().map(BuildPromotionOwner::getBuildPromotion).filter(b -> ((BuildPromotionEx)b).hasArtifactDependencyOn(item)));
          }
          if (myStateLocator != null && isStateIncluded(myStateLocator, STATE_RUNNING)) {
            result = Stream.concat(result, myBuildsManager.getRunningBuilds().stream().map(BuildPromotionOwner::getBuildPromotion).filter(b -> ((BuildPromotionEx)b).hasArtifactDependencyOn(item)));
          }
          return Stream.concat(result, build.getProvidedArtifacts().getArtifacts().keySet().stream().map(v -> ((SBuild)v).getBuildPromotion()))
                       .sorted(new Build.BuildPromotionDependenciesComparator()).distinct().collect(Collectors.toList());
      };
    }
  }

  private class BuildPromotionOrderedFinder extends GraphFinder<BuildPromotion> {
    public BuildPromotionOrderedFinder(@NotNull final BuildPromotionFinder finder) {
      super(finder, new BuildPromotionOrderSupportTraverser());
    }

    @Override
    protected void collectLinked(@NotNull final Set<BuildPromotion> result,
                                 @NotNull final Collection<BuildPromotion> toProcess,
                                 @NotNull final Collection<BuildPromotion> stopItems,
                                 final Long lookupLimit,
                                 @NotNull final LinkRetriever<BuildPromotion> linkRetriever,
                                 final boolean recursive) {
      if (!recursive) {
        throw new BadRequestException("Builds traversal is only supported in 'recursive:true' mode");
      }

      for (BuildPromotion item : toProcess) {
        if (stopItems.contains(item)) {
          result.add(item);
        } else {
          final List<BuildPromotion> linked = linkRetriever.getLinked(item);
          for (BuildPromotion promotion : linked) {
            result.add(promotion);
            if (stopItems.contains(item)) {
              break;
            }
          }
        }
      }
    }
  }

  private class BuildPromotionOrderSupportTraverser implements GraphFinder.Traverser<BuildPromotion> {
    @NotNull
    @Override
    public GraphFinder.LinkRetriever<BuildPromotion> getChildren() {
      return item -> {
          final SBuildType buildType = item.getBuildType();
          if (buildType == null) {
            return Collections.emptyList();
          }
          final SBuild associatedBuild = item.getAssociatedBuild();
          if (associatedBuild != null) {
            final List<OrderedBuild> buildsBefore = ((BuildTypeEx)buildType).getBuildTypeOrderedBuilds().getBuildsBefore(associatedBuild);
            //consider performance optimization by converting id to build only on actual retrieve
            return getBuildPromotions(buildsBefore);
          } else {
            final Branch branch = item.getBranch();
            if (branch == null) {
              return Collections.emptyList();
            }
            final List<OrderedBuild> buildsBefore = ((BuildTypeEx)buildType).getBuildTypeOrderedBuilds().getBuildsBeforeInBranches(item, new Filter<String>() {
              @Override
              public boolean accept(@NotNull String data) {
                return true;
              }
            });
            return getBuildPromotions(buildsBefore);
        }
      };
    }

    @NotNull
    @Override
    public GraphFinder.LinkRetriever<BuildPromotion> getParents() {
      return item -> {
          final SBuildType buildType = item.getBuildType();
          if (buildType == null) {
            return Collections.emptyList();
          }
          final SBuild associatedBuild = item.getAssociatedBuild();
          if (associatedBuild != null) {
            final List<OrderedBuild> buildsBefore = ((BuildTypeEx)buildType).getBuildTypeOrderedBuilds().getBuildsAfter(associatedBuild);
            return getBuildPromotions(buildsBefore);
          } else {
            return Collections.emptyList();
          }
      };
    }
  }

  @NotNull
  public ItemsProviders.ItemsProvider<BuildPromotion> getLazyResult() {
    return new ItemsProviders.ItemsProvider<BuildPromotion>() {
      @NotNull
      @Override
      public List<BuildPromotion> getItems(@Nullable final String locatorText) {
        return BuildPromotionFinder.this.getItems(locatorText).myEntries;
      }

      @Nullable
      @Override
      public Integer getCheapCount(@Nullable final String locatorText) {
        /*
        This emulates build's isUsedByOtherBuilds() via a request like:
            .../app/rest/builds?fields=build(id,related(builds(count,$locator(count:1,defaultFilter:false,item:(count:1,defaultFilter:false,snapshotDependency:(from:(id:$context.build.id),recursive:false)),item:(count:1,defaultFilter:false,artifactDependency:(from:(id:$context.build.id),recursive:false))))))
         */
        Locator locator;
        try {
          locator = createLocator(locatorText, null);
        } catch (Exception e) {
          return null;
        }
        setLocatorDefaults(locator);
        Long count = locator.getSingleDimensionValueAsLong(PagerData.COUNT);

        Integer result = null;

        final List<String> itemDimension = locator.getDimensionValue(DIMENSION_ITEM);
        if (!itemDimension.isEmpty()) {
          if (count != null) {
            int[] max = new int[1];
            max[0] = -1;
            boolean exceedsCount = itemDimension.stream().map(l -> getCheapCount(l)).anyMatch(cheapCount -> {
              if (cheapCount == null) {
                max[0] = -2; //there is not cheap count
                return false;
              }
              if (max[0] != -2) {
                max[0] = Math.max(max[0], cheapCount);
              }
              return cheapCount >= count;
            });
            if (exceedsCount) {
              result = count.intValue();
            } else if (max[0] == 0) { //all counts were cheap and all were 0
              result = 0;
            }
          }
        } else {
          //optimization method counts all builds and these can be set by defaultFilter
          if (locator.getSingleDimensionValueAsBoolean(PERSONAL) != null) return null;
          if (locator.getSingleDimensionValueAsBoolean(CANCELED) != null) return null;
          if (locator.getSingleDimensionValueAsBoolean(FAILED_TO_START) != null) return null;
          if (locator.getSingleDimensionValueAsBoolean(BRANCH) != null) return null;

          final String snapshotDepDimension = locator.getSingleDimensionValue(SNAPSHOT_DEP);
          if (snapshotDepDimension != null) {
            result = getSnapshotRelatedBuildsCheapCount(snapshotDepDimension, count == null ? null : count.intValue());
          }
          if (result == null) {
            if (count == null) {
              return null;
            }
            final String artifactDepDimension = locator.getSingleDimensionValue(ARTIFACT_DEP);
            if (artifactDepDimension != null) {
              result = getArtifactRelatedBuildsCheapCount(artifactDepDimension, count.intValue());
            }
          }
        }

        if (!locator.getUnusedDimensions().isEmpty()) return null;

        if (result == null) return null;
        return count != null ? Math.min(count.intValue(), result) : result;
      }
    };
  }

  @Nullable
  private Integer getSnapshotRelatedBuildsCheapCount(@NotNull final String snapshotDepDimension, @Nullable final Integer limitingCount) {
    final GraphFinder<BuildPromotion> graphFinder = new GraphFinder<BuildPromotion>(this, SNAPSHOT_DEPENDENCIES_TRAVERSER);
    GraphFinder.ParsedLocator<BuildPromotion> parsedLocator = graphFinder.getParsedLocator(snapshotDepDimension);

    Integer count = parsedLocator.getCount();
    if (count == null && limitingCount != null) {
      count = limitingCount;
    } else if (count != null && limitingCount != null) {
      count = Math.min(count, limitingCount);
    }

    List<BuildPromotion> fromItems = parsedLocator.getFromItems();
    Optional<Integer> maxOptional = fromItems.stream().map(b -> b.getNumberOfDependedOnMe()).max(Integer::compare);  //cannot sum as they can intersect, so using max
    //can optimize by finding first greater then "count" if set
    int result = 0;
    if (maxOptional.isPresent()) {
      result = maxOptional.get();
    }
    boolean recursive = parsedLocator.isRecursive();
    if (parsedLocator.isIncludeInitial()) result++;
    if (!parsedLocator.isAllDimensionsUsed()) return null;
    if (count != null && count <= result) return count;
    if (!recursive) {
      return count != null ? Math.min(count, result) : result;
    }
    return null;
  }

  @Nullable
  private Integer getArtifactRelatedBuildsCheapCount(@NotNull final String artifactDepDimension, @Nullable final Integer limitingCount) {
    final GraphFinder<BuildPromotion> graphFinder = new GraphFinder<BuildPromotion>(this, new ArtifactDepsTraverser(null));
    GraphFinder.ParsedLocator<BuildPromotion> parsedLocator = graphFinder.getParsedLocator(artifactDepDimension);

    Integer count = parsedLocator.getCount();
    if (count == null && limitingCount != null) {
      count = limitingCount;
    } else if (count != null && limitingCount != null) {
      count = Math.min(count, limitingCount);
    }

    if (count == null || count > 2) return null;

    List<BuildPromotion> fromItems = parsedLocator.getFromItems();
    DownloadedArtifactsLogger artifactsLogger = myServiceLocator.getSingletonService(DownloadedArtifactsLogger.class);
    // there is a small bug where the count here can be different from the non-lazy request:
    //  this logic does not consider queued with artifact dependency on the "from" builds and also no running builds with the same deps which have not yet downloaded the artifacts
    if (fromItems.stream().map(b -> b.getAssociatedBuildId()).filter(Objects::nonNull).noneMatch(id -> artifactsLogger.buildArtifactsWereDownloaded(id))) {
      return 0;
    }
    int result = 1;
    parsedLocator.isRecursive(); //just all and mark as used - can only increase the number
    if (parsedLocator.isIncludeInitial()) result++;
    if (!parsedLocator.isAllDimensionsUsed()) return null;
    if (count <= result) return count;
    return null;
  }

  @NotNull
  private List<BuildPromotion> getBuildPromotions(@NotNull List<OrderedBuild> orderedBuilds) {
    List<BuildPromotion> res = new ArrayList<>();
    List<Long> promotionIds = orderedBuilds.stream().map(b -> b.getPromotionId()).collect(Collectors.toList());
    Map<Long, BuildPromotion> promosMap = myBuildPromotionManager.findPromotionsByIds(promotionIds).stream().collect(Collectors.toMap(k -> k.getId(), p -> p));
    for (OrderedBuild ob: orderedBuilds) {
      BuildPromotion bp = promosMap.get(ob.getPromotionId());
      if (bp != null) res.add(bp);
    }
    return res;
  }
}
