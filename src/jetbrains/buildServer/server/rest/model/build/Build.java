/*
 * Copyright 2000-2022 JetBrains s.r.o.
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

package jetbrains.buildServer.server.rest.model.build;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import jetbrains.buildServer.AgentRestrictor;
import jetbrains.buildServer.AgentRestrictorType;
import jetbrains.buildServer.BuildAgent;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.artifacts.RevisionRule;
import jetbrains.buildServer.artifacts.RevisionRules;
import jetbrains.buildServer.controllers.changes.ChangesBean;
import jetbrains.buildServer.controllers.changes.ChangesPopupUtil;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.parameters.PasswordParametersFilterCore;
import jetbrains.buildServer.parameters.impl.MapParametersProviderImpl;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.ParameterCondition;
import jetbrains.buildServer.server.rest.data.PermissionChecker;
import jetbrains.buildServer.server.rest.data.build.TagFinder;
import jetbrains.buildServer.server.rest.data.change.BuildChangeData;
import jetbrains.buildServer.server.rest.data.change.SVcsModificationOrChangeDescriptor;
import jetbrains.buildServer.server.rest.data.finder.FinderImpl;
import jetbrains.buildServer.server.rest.data.finder.impl.*;
import jetbrains.buildServer.server.rest.data.problem.ProblemOccurrenceFinder;
import jetbrains.buildServer.server.rest.data.problem.TestOccurrenceFinder;
import jetbrains.buildServer.server.rest.errors.*;
import jetbrains.buildServer.server.rest.model.Properties;
import jetbrains.buildServer.server.rest.model.*;
import jetbrains.buildServer.server.rest.model.agent.Agent;
import jetbrains.buildServer.server.rest.model.agent.Agents;
import jetbrains.buildServer.server.rest.model.build.approval.ApprovalInfo;
import jetbrains.buildServer.server.rest.model.build.downloadedArtifacts.DownloadedArtifacts;
import jetbrains.buildServer.server.rest.model.buildType.BuildType;
import jetbrains.buildServer.server.rest.model.buildType.PropEntitiesArtifactDep;
import jetbrains.buildServer.server.rest.model.change.BuildChanges;
import jetbrains.buildServer.server.rest.model.change.Changes;
import jetbrains.buildServer.server.rest.model.change.Revision;
import jetbrains.buildServer.server.rest.model.change.Revisions;
import jetbrains.buildServer.server.rest.model.files.FileApiUrlBuilder;
import jetbrains.buildServer.server.rest.model.files.Files;
import jetbrains.buildServer.server.rest.model.issue.IssueUsages;
import jetbrains.buildServer.server.rest.model.problem.ProblemOccurrences;
import jetbrains.buildServer.server.rest.model.problem.TestOccurrences;
import jetbrains.buildServer.server.rest.model.user.User;
import jetbrains.buildServer.server.rest.request.*;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelExperimental;
import jetbrains.buildServer.server.rest.util.*;
import jetbrains.buildServer.serverSide.Branch;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.agentPools.AgentPool;
import jetbrains.buildServer.serverSide.agentTypes.AgentTypeFinder;
import jetbrains.buildServer.serverSide.agentTypes.SAgentType;
import jetbrains.buildServer.serverSide.artifacts.SArtifactDependency;
import jetbrains.buildServer.serverSide.audit.ActionType;
import jetbrains.buildServer.serverSide.audit.AuditLogAction;
import jetbrains.buildServer.serverSide.auth.AccessDeniedException;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.buildDistribution.WaitReason;
import jetbrains.buildServer.serverSide.dependency.BuildDependency;
import jetbrains.buildServer.serverSide.impl.BaseBuild;
import jetbrains.buildServer.serverSide.impl.DownloadedArtifactsLoggerImpl;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.serverSide.impl.approval.ApprovableBuildManager;
import jetbrains.buildServer.serverSide.impl.audit.filters.ActionTypesFilter;
import jetbrains.buildServer.serverSide.impl.changeProviders.ArtifactDependencyChangesProvider;
import jetbrains.buildServer.serverSide.impl.problems.BuildProblemImpl;
import jetbrains.buildServer.serverSide.metadata.BuildMetadataEntry;
import jetbrains.buildServer.serverSide.metadata.impl.MetadataStorageEx;
import jetbrains.buildServer.serverSide.parameters.types.PasswordsSearcher;
import jetbrains.buildServer.serverSide.problems.BuildProblem;
import jetbrains.buildServer.serverSide.userChanges.CanceledInfo;
import jetbrains.buildServer.serverSide.vcs.VcsLabelManager;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModel;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.PasswordReplacer;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.browser.Element;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.impl.RevisionsNotFoundException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: Yegor Yarko
 * Date: 29.03.2009
 */
@SuppressWarnings("WeakerAccess")
@XmlRootElement(name = "build")
/*Comments inside propOrder: q = queued, r = running, f = finished*/
@XmlType(name = "build",
  propOrder = {"id", "promotionId", "buildTypeId", "buildTypeInternalId", "number"/*rf*/, "status"/*rf*/, "state", "running"/*r*/, "composite",
    "failedToStart"/*f*/,
    "personal", "percentageComplete"/*r*/, "branchName", "defaultBranch", "unspecifiedBranch", "history", "pinned"/*rf*/, "href", "webUrl",
    "queuePosition"/*q*/, "limitedChangesCount", "artifactsDirectory" /*experimental*/,
    "links",
    "statusText"/*rf*/,
    "buildType", "comment", "tags", "pinInfo"/*f*/, "personalBuildUser",
    "startEstimate"/*q*/, "waitReason"/*q*/, "finishEstimate"/*q*/, "delayedByBuild"/*q*/, "plannedAgent"/*q*/,
    "approvalInfo"/*r*/, "runningBuildInfo"/*r*/, "canceledInfo"/*rf*/,
    "queuedDate", "startDate"/*rf*/, "finishDate"/*f*/,
    "triggered", "lastChanges", "changes", "revisions", "versionedSettingsRevision", "artifactDependencyChanges" /*experimental*/,
    "agent", "compatibleAgents"/*q*/,
    "testOccurrences"/*rf*/, "problemOccurrences"/*rf*/,
    "artifacts"/*rf*/, "issues"/*rf*/,
    "properties", "resultingProperties", "originalProperties", "attributes", "statistics", "metadata"/*rf*/,
    "buildDependencies", "buildArtifactDependencies", "customBuildArtifactDependencies"/*q*/,
    "settingsHash", "currentSettingsHash", "modificationId", "chainModificationId", "replacementIds",
    "related", /*experimental*/
    "triggeringOptions"/*only when triggering*/,
    "usedByOtherBuilds" /*experimental*/,
    "statusChangeComment" /*experimental, temporary*/,
    "vcsLabels",
    "detachedFromAgent",
    "finishOnAgentDate",
    "customized", /* Probably to be removed, replace with customization */
    "customization",
    "changesCollectingInProgress" /*experimental*/,
    "queuedWaitReasons", /*q experimental */
    "downloadedArtifacts", /*rf experimental*/
    "parallelized", /*qrf experimental */
    "firstBuildWithSameChanges"
  })
@ModelDescription("Represents a build instance.")
public class Build {
  private static final Logger LOG = Logger.getInstance(Build.class.getName());

  private static final String FINISH_ESTIMATE = "finishEstimate";

  public static final String CANCELED_INFO = "canceledInfo";
  public static final String PROMOTION_ID = "taskId";
  public static final String REST_BEANS_BUILD_INCLUDE_ALL_ATTRIBUTES = "rest.beans.build.includeAllAttributes";
  public static final String QUEUE_WAIT_REASON_STAT_PREFIX = "queueWaitReason:";

  @NotNull protected final BuildPromotion myBuildPromotion;
  @Nullable protected final SBuild myBuild;
  @Nullable private final SQueuedBuild myQueuedBuild;

  @NotNull protected final Fields myFields;
  @NotNull private final BeanContext myBeanContext;
  @NotNull private final ServiceLocator myServiceLocator;

  @SuppressWarnings("ConstantConditions")
  public Build() {
    myBuildPromotion = null;
    myBuild = null;
    myQueuedBuild = null;

    myFields = null;
    myBeanContext = null;
    myServiceLocator = null;
  }

  public Build(@NotNull final SBuild build, @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    myBuild = build;
    myBuildPromotion = myBuild.getBuildPromotion();
    myQueuedBuild = null;

    myBeanContext = beanContext;
    myServiceLocator = beanContext.getServiceLocator();
    myFields = fields;
  }

  public Build(@NotNull final BuildPromotion buildPromotion, @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    myBuildPromotion = buildPromotion;
    myQueuedBuild = myBuildPromotion.getQueuedBuild();
    myBuild = myQueuedBuild != null ? null : myBuildPromotion.getAssociatedBuild();

    if (myQueuedBuild == null && myBuild == null) { //diagnostics for TW-41263
      final long currentId = myBuildPromotion.getId();
      final BuildPromotion replacement = beanContext.getSingletonService(BuildPromotionManager.class).findPromotionOrReplacement(currentId);
      if (replacement == null) {
        LOG.info("Promotion with id " + currentId + " was removed during request processing");
      } else if (replacement.getId() != currentId) {
        LOG.info("Promotion with id " + currentId + " was replaced by promotion with id " + replacement.getId() + " during request processing");
        //while we can return new build here, this does not seem a good idea as this way a collection of builds can contain builds from before and after replacement making it inconsistent
        //reporting an error is probably also not a good thing to do as this can prevent from getting any information via the request
      }
    }

    myBeanContext = beanContext;
    myServiceLocator = beanContext.getServiceLocator();
    myFields = fields;
  }

  public static Build getNoPermissionsBuild(@NotNull final SBuild build, @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    return new Build(build, Fields.NONE, beanContext);
  }

  @XmlElement
  public List<VcsLabel> getVcsLabels() {
    boolean isIncluded = myFields.isIncluded("vcsLabels", false, true);
    return ValueWithDefault.decideDefault(isIncluded, () -> {
      if(myBuild == null) {
        return Collections.emptyList();
      }

      VcsLabelManager labelManager = myBeanContext.getSingletonService(VcsLabelManager.class);
      Fields labelFields = myFields.getNestedField("vcsLabels");

      return labelManager.getLabels(myBuild).stream()
                         .map(l -> new VcsLabel(l, labelFields, myBeanContext))
                         .collect(Collectors.toList());
    });
  }

  @XmlAttribute
  public Long getId() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("id", true), () -> {
      // since 9.0 promotionId == buildId (apart from https://youtrack.jetbrains.com/issue/TW-38777), so assume so for queued builds
      return myBuild != null ? myBuild.getBuildId() : myBuildPromotion.getId();
    });
  }

  @XmlAttribute(name = PROMOTION_ID)
  public Long getPromotionId() {
    return ValueWithDefault.decideDefault(myFields.isIncluded(PROMOTION_ID, false, false), myBuildPromotion.getId());
  }

  public enum BuildState {
    queued, finished, running, deleted, unknown
  }

  /**
   * The current state of the build: one of "queued", "running", "finished"
   * Can aso be "deleted" for just deleted builds
   */
  @XmlAttribute
  public BuildState getState() {
    if (!myFields.isIncluded("state", true, true)) {
      return null;
    }
    if (myQueuedBuild != null) return BuildState.queued;
    if (myBuild != null) {
      if (myBuild.isFinished()) {
        return BuildState.finished;
      } else {
        return BuildState.running;
      }
    }
    if (((BuildPromotionEx)myBuildPromotion).isDeleted()) return BuildState.deleted;
    return BuildState.unknown;
  }

  /**
   * @return
   * @deprecated use "state" instead
   */
  @XmlAttribute
  public Boolean isRunning() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("running", false, false), () -> myBuild != null && !myBuild.isFinished());
  }

  /**
   * Experimental
   * "composite" build state (since TeamCty 2017.2)
   */
  @XmlAttribute
  public Boolean isComposite() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("composite"), myBuildPromotion::isCompositeBuild);
  }

  @XmlAttribute
  public Boolean isFailedToStart() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("failedToStart"), () -> myBuild != null && myBuild.isInternalError());
  }

  @XmlAttribute
  public String getNumber() {
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("number", true), myBuild::getBuildNumber);
  }

  @XmlAttribute
  public String getHref() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("href", true), () -> {
      String result = null;
      if (myBuild != null) {
        result = myBeanContext.getApiUrlBuilder().getHref(myBuild);
      } else if (myQueuedBuild != null) {
        result = myBeanContext.getApiUrlBuilder().getHref(myQueuedBuild);
      }
      return result;
    });
  }

  /**
   * Build status, present only for running or finished builds.
   * One of "SUCCESS" or "FAILURE"
   * Can be "UNKNOWN" for a canceled build
   */
  @XmlAttribute
  public String getStatus() {
    //todo: consider getting details from full statistics is that is required for the node as otherwise the text and test counts will be not in sync
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("status", true), () -> myBuild.getBuildStatus().getText());
  }

  @XmlAttribute
  public Boolean isHistory() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("history"), () -> myBuildPromotion.isOutOfChangesSequence());
  }

  @XmlElement(name = "firstBuildWithSameChanges")
  @Nullable
  public Build getFirstBuildWithSameChanges() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("firstBuildWithSameChanges", false, false),
      () -> {
        SBuild sequenceBuild = ((BuildPromotionEx) myBuildPromotion).getSequenceBuild();
        if(sequenceBuild == null || sequenceBuild.getBuildPromotion().equals(myBuildPromotion)) {
          return null;
        }

        return new Build(sequenceBuild, myFields.getNestedField("firstBuildWithSameChanges"), myBeanContext);
      }
    );
  }

  @XmlAttribute
  public Boolean isPinned() {
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("pinned"), () -> myBuild.isPinned());
  }

  @XmlAttribute
  public String getBranchName() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("branchName"), () -> {
      Branch branch = myBuildPromotion.getBranch();
      return branch == null ? null : branch.getDisplayName();
    });
  }

  @XmlAttribute
  public Boolean getDefaultBranch() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("defaultBranch"), () -> {
      Branch branch = myBuildPromotion.getBranch();
      return branch == null ? null : branch.isDefaultBranch();
    });
  }

  @XmlAttribute
  public Boolean getUnspecifiedBranch() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("unspecifiedBranch"), () -> {
      Branch branch = myBuildPromotion.getBranch();
      if (branch == null) return null;
      return Branch.UNSPECIFIED_BRANCH_NAME.equals(branch.getName());
    });
  }

  @XmlAttribute
  public Boolean isPersonal() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("personal"), myBuildPromotion::isPersonal);
  }

  /**
   * Experimental, will be dropped in the future
   * True if there are other builds which either snapshot-depend on this one, or downloaded artifacts from this one
   */
  @XmlAttribute
  public Boolean isUsedByOtherBuilds() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("usedByOtherBuilds", false, false), () -> myBuild != null && myBuild.isUsedByOtherBuilds());
  }

  @XmlAttribute
  public String getWebUrl() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("webUrl", true), () -> {
      String result = null;
      if (myBuild != null) {
        result = myServiceLocator.getSingletonService(WebLinks.class).getViewResultsUrl(myBuild);
      } else if (myQueuedBuild != null) {
        result = myServiceLocator.getSingletonService(WebLinks.class).getQueuedBuildUrl(myQueuedBuild);
      }
      return result;
    });
  }

  @XmlAttribute
  public String getBuildTypeId() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("buildTypeId", true), () -> {
      final String buildTypeExternalId = myBuildPromotion.getBuildTypeExternalId();
      if (!BuildPromotion.NOT_EXISTING_BUILD_TYPE_ID.equals(buildTypeExternalId)) {
        return buildTypeExternalId;
      } else {
        return null;
      }
    });
  }

  @XmlAttribute
  public String getBuildTypeInternalId() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("buildTypeInternalId", false, false), () -> myBuildPromotion.getBuildTypeId());
  }

  @XmlElement
  public Links getLinks() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("links", false, false), () -> {
      WebLinks webLinks = myBeanContext.getSingletonService(WebLinks.class);
      RelativeWebLinks relativeWebLinks = new RelativeWebLinks();
      Links.LinksBuilder builder = new Links.LinksBuilder();
      if (myBuild != null) {
        builder.add(Link.WEB_VIEW_TYPE, webLinks.getViewResultsUrl(myBuild), relativeWebLinks.getViewResultsUrl(myBuild));
      } else if (myQueuedBuild != null) {
        builder.add(Link.WEB_VIEW_TYPE, webLinks.getQueuedBuildUrl(myQueuedBuild), relativeWebLinks.getQueuedBuildUrl(myQueuedBuild));
      }
      return builder.build(myFields.getNestedField("links"));
    });
  }

  @XmlElement
  public String getStatusText() {
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("statusText", false, true), () -> myBuild.getStatusDescriptor().getText());
  }

  @XmlElement(name = "agent")
  public Agent getAgent() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("agent", false), () -> {
      if (myBuild != null) {
        if (myBuild.isAgentLessBuild()) return null;
        SBuildAgent agent = myBuild.getAgent();
        return new Agent(agent, myFields.getNestedField("agent"), myBeanContext);
      }
      if (myQueuedBuild != null) {
        final AgentRestrictor agentRestrictor = myQueuedBuild.getAgentRestrictor();
        if (agentRestrictor != null) {
          if (agentRestrictor.getType() == AgentRestrictorType.SINGLE_AGENT) {
            SBuildAgent agent = myQueuedBuild.getBuildAgent();
            if (agent != null) {
              return new Agent(agent, myFields.getNestedField("agent"), myBeanContext);
            }
          }
          if (agentRestrictor.getType() == AgentRestrictorType.AGENT_POOL) {
            final int agentPoolId = agentRestrictor.getId();
            final AgentPool agentPool = myBeanContext.getSingletonService(AgentPoolFinder.class).getAgentPoolById(agentPoolId);
            return new Agent(agentPool, myFields.getNestedField("agent"), myBeanContext);
          }
          if (agentRestrictor.getType() == AgentRestrictorType.CLOUD_IMAGE) {
            final int agentTypeId = agentRestrictor.getId();
            final SAgentType agentType = AgentFinder.getAgentType(String.valueOf(agentTypeId), myBeanContext.getSingletonService(AgentTypeFinder.class));
            return new Agent(agentType, myFields.getNestedField("agent"), myBeanContext);
          }
        }
      }
      return null;
    });
  }

  @XmlElement(name = "buildType")
  public BuildType getBuildType() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("buildType", false), () -> {
      final SBuildType buildType = myBuildPromotion.getParentBuildType();
      return buildType == null ? null : new BuildType(new BuildTypeOrTemplate(buildType), myFields.getNestedField("buildType"), myBeanContext);
    });
  }

  @XmlElement
  public String getStartDate() { // consider adding myBuild.getServerStartDate()
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("startDate", false), () -> Util.formatTime(myBuild.getStartDate()));
  }

  @XmlElement
  public String getFinishDate() {
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("finishDate", false), () -> Util.formatTime(myBuild.getFinishDate()));
  }

  @XmlElement(defaultValue = "") //todo: remove comment
  public Comment getComment() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("comment", false), () -> {
      jetbrains.buildServer.serverSide.comments.Comment comment = myBuildPromotion.getBuildComment();
      return comment == null ? null : new Comment(comment, myFields.getNestedField("comment", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  /**
   * This is a temporary workaround, will be removed in the future versions
   */
  @XmlElement
  public Comment getStatusChangeComment() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("statusChangeComment", false, false), () -> {
      //can improve the code by requesting only 1 item
      final List<AuditLogAction> logActions = ((BuildPromotionEx)myBuildPromotion).getAuditLogActions(new ActionTypesFilter(ActionType.BUILD_MARKED_AS_FAILED, ActionType.BUILD_MARKED_AS_SUCCESSFUL));
      if (logActions.isEmpty()) return null;
      AuditLogAction action = logActions.get(0); //the most recent action
      return new Comment(action.getComment(), myFields.getNestedField("statusChangeComment", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  @XmlElement
  public Tags getTags() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("tags", false), () -> {
      final Fields fields = myFields.getNestedField("tags", Fields.NONE, Fields.LONG);
      final TagFinder tagFinder = new TagFinder(myBeanContext.getSingletonService(UserFinder.class), myBuildPromotion);
      return new Tags(tagFinder.getItems(fields.getLocator(), TagFinder.getDefaultLocator()).getEntries(), fields, myBeanContext);
    });
  }

  @XmlElement(name = "pinInfo")
  public Comment getPinInfo() {
    if (myBuild == null || !myBuild.isPinned()) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("pinInfo", false), () -> {
      final jetbrains.buildServer.serverSide.comments.Comment pinComment = getPinComment(myBuild);
      if (pinComment == null) return null;
      return new Comment(pinComment, myFields.getNestedField("pinInfo", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  @Nullable
  public static jetbrains.buildServer.serverSide.comments.Comment getPinComment(@Nullable final SBuild build) {
    if (build == null || !(build instanceof SFinishedBuild)) return null;
    SFinishedBuild finishedBuild = (SFinishedBuild)build;  //TeamCity API: getPinComment() is only available for finished build, while isPinned is available for running
    final jetbrains.buildServer.serverSide.comments.Comment pinComment = finishedBuild.getPinComment();
    if (pinComment == null) {
      return null;
    }
    return pinComment;
  }

  @XmlElement
  public Properties getProperties() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("properties", false), () -> {
      checkCanViewRuntimeData();
      return new Properties(Properties.createEntity(myBuildPromotion.getParameters(), myBuildPromotion.getCustomParameters()), null,
                            null, myFields.getNestedField("properties", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  @XmlElement
  public Properties getOriginalProperties() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("originalProperties", false), () -> {
      checkCanViewRuntimeData();
      return new Properties(Properties.createEntity(myBuildPromotion.getDefaultParameters(), null), null,
                            null, myFields.getNestedField("originalProperties", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  /**
   * Experimental
   */
  @XmlElement
  public Properties getResultingProperties() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("resultingProperties", false, false), () -> {
      checkCanViewRuntimeData();
      return new Properties(getBuildResultingParameters(myBuildPromotion, myServiceLocator), null,
                            myFields.getNestedField("resultingProperties", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  @NotNull
  public static ParametersProvider getBuildResultingParameters(@NotNull BuildPromotion buildPromotion, @NotNull ServiceLocator serviceLocator) {
    SBuild build = buildPromotion.getAssociatedBuild();
    if (build != null && build instanceof BaseBuild) {
      try {
        Map<String, String> parameters = ((BaseBuild)build).getBuildFinishParameters();
        if (parameters == null) {
          parameters = ((BaseBuild)build).getBuildStartParameters();
        }
        if (parameters != null) {
          return new MapParametersProviderImpl(parameters);
        }
      } catch (ClassCastException ignore) {
      }
    }
    //falling back to recalculated parameters
    return calculateAllParameters(buildPromotion, serviceLocator.findSingletonService(PasswordsSearcher.class));
  }

  private static ParametersProvider calculateAllParameters(@NotNull final BuildPromotion buildPromotion, @NotNull PasswordsSearcher searcher) {
    Set<String> passwords = searcher.collectPasswords(buildPromotion);
    ParametersProvider provider = ((BuildPromotionEx)buildPromotion).getParametersProvider();
    if (passwords.isEmpty()) {
      return provider;
    }
    PasswordReplacer passwordReplacer = PasswordParametersFilterCore.createPasswordReplacer(passwords);
    return new ParametersProvider() {
      @Nullable @Override public String get(@NotNull final String key) { return Util.resolveNull(provider.get(key), passwordReplacer::replacePasswords);}
      @Override public int size() { return provider.size();}
      @Override public Map<String, String> getAll() {
        return provider.getAll().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> passwordReplacer.replacePasswords(e.getValue())));
      }
    };
  }

  @XmlElement
  public Entries getAttributes() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("attributes", false), () -> {
      checkCanViewRuntimeData();
      final LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
      Fields nestedFields = myFields.getNestedField("attributes", Fields.LONG, Fields.LONG);
      String locator = ParameterCondition.getNameAndNotEmptyValueLocator(BuildAttributes.CLEAN_SOURCES);
      boolean supportCustomLocator = TeamCityProperties.getPropertyOrNull(REST_BEANS_BUILD_INCLUDE_ALL_ATTRIBUTES) == null;
      if (supportCustomLocator) {
        String locatorFromFields = nestedFields.getLocator();
        if (locatorFromFields != null) locator = locatorFromFields;
      } else if (TeamCityProperties.getBoolean(REST_BEANS_BUILD_INCLUDE_ALL_ATTRIBUTES)) {
        locator = null; //include all
      }
      final ParameterCondition parameterCondition = ParameterCondition.create(locator);
      final Map<String, Object> buildAttributes = ((BuildPromotionEx)myBuildPromotion).getAttributes();
      for (Map.Entry<String, Object> attribute : buildAttributes.entrySet()) {
        if (parameterCondition == null || parameterCondition.parameterMatches(new SimpleParameter(attribute.getKey(), attribute.getValue().toString()), null)) {
          result.put(attribute.getKey(), attribute.getValue().toString());
        }
      }
      return new Entries(result, nestedFields);
    });
  }

  @XmlElement
  public Properties getStatistics() {
    if (myBuild == null) {
      return null;
    } else {
      final String statisticsHref = myBeanContext.getApiUrlBuilder().getHref(myBuild) + BuildRequest.STATISTICS;
      return ValueWithDefault.decideDefault(myFields.isIncluded("statistics", false), () -> {
        final Fields nestedField = myFields.getNestedField("statistics");
        return new Properties(nestedField.isMoreThenShort() ? getBuildStatisticsValues(myBuild) : null, //for performance reasons
                              statisticsHref, nestedField, myBeanContext);
      });
    }
  }

  @XmlElement
  public NamedDatas getMetadata() {
    if (myBuild == null) {
      return null;
    } else {
      return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("metadata", false, false), () -> {
        checkCanViewRuntimeData();
        HashMap<String, Map<String, String>> result = new HashMap<>();
        MetadataStorageEx metadataStorage = myServiceLocator.getSingletonService(MetadataStorageEx.class);
        for (String providerId : metadataStorage.getProviderIds()) {
          Iterator<BuildMetadataEntry> metadataEntryIterator = metadataStorage.getBuildEntry(myBuild.getBuildId(), providerId);
          while (metadataEntryIterator.hasNext()) {
            BuildMetadataEntry metadataEntry = metadataEntryIterator.next();
            HashMap<String, String> properties = new HashMap<>(metadataEntry.getMetadata());
            if (properties.get(".providerId") == null) {
              properties.put(".providerId", providerId);
            } else {
              properties.put(".teamcity.rest.providerId", providerId); // assume clash here does not happen
            }
            if (properties.get(".key") == null) {
              properties.put(".key", metadataEntry.getKey());
            } else {
              properties.put(".teamcity.rest.key", metadataEntry.getKey());  // assume clash here does not happen
            }
            result.put(providerId + "_" + metadataEntry.getKey(), properties);
          }
        }
        return new NamedDatas(result, myFields.getNestedField("metadata"));
      });
    }
  }

  @NotNull
  public static Map<String, String> getBuildStatisticsValues(@NotNull final SBuild build) {
    final Map<String, BigDecimal> values = build.getStatisticValues();

    final Map<String, String> result = new HashMap<String, String>(values.size());
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      result.put(entry.getKey(), entry.getValue().stripTrailingZeros().toPlainString());
    }

    return result;
  }

  @NotNull
  public static String getBuildStatisticValue(@NotNull final SBuild build, @NotNull final String statisticValueName) {
    Map<String, String> stats = getBuildStatisticsValues(build);
    String val = stats.get(statisticValueName);
    if (val != null) {
      return val;
    }
    BigDecimal directValue = build.getStatisticValue(statisticValueName); //TeamCity API issue: this can actually provide a value which is not returned in the list
    if (directValue != null) {
      return directValue.stripTrailingZeros().toPlainString();
    }
    throw new NotFoundException("No statistics data for key: " + statisticValueName + "' in build " + LogUtil.describe(build));
  }

  /**
   * @deprecated See @getRunningBuildInfo
   */
  @XmlAttribute
  public Integer getPercentageComplete() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("percentageComplete"), () -> {
      if (myBuild == null || myBuild.isFinished()) {
        return null;
      }
      SRunningBuild runningBuild = (SRunningBuild)myBuild;
      return runningBuild.getCompletedPercent();
    });
  }

  @XmlElement(name = "approvalInfo")
  public ApprovalInfo getApprovalInfo() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("approvalInfo", false), () -> {
      ApprovableBuildManager approvableBuildManager = myBeanContext.getSingletonService(ApprovableBuildManager.class);
      BuildPromotionEx buildPromotionEx = (BuildPromotionEx)myBuildPromotion;

      if (approvableBuildManager.hasApprovalFeature(buildPromotionEx)) {
        return new ApprovalInfo(buildPromotionEx, myFields.getNestedField("approvalInfo"), myBeanContext);
      }
      return null;
    });
  }

  @XmlElement(name = "running-info")
  public RunningBuildInfo getRunningBuildInfo() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("running-info", false), () -> {
      SRunningBuild runningBuild = getRunningBuild(myBuildPromotion, myServiceLocator);
      if (runningBuild == null) return null;
      return new RunningBuildInfo(runningBuild, myFields.getNestedField("running-info"), myBeanContext);
    });
  }

  @Nullable
  public static SRunningBuild getRunningBuild(@NotNull final BuildPromotion buildPromotion, final ServiceLocator serviceLocator) {
    final SBuild build = buildPromotion.getAssociatedBuild();
    if (build == null || build.isFinished()) {
      return null;
    }
    return serviceLocator.getSingletonService(RunningBuildsManager.class).findRunningBuildById(build.getBuildId());
  }

  @XmlElement(name = "snapshot-dependencies")
  public Builds getBuildDependencies() {
    if (!myFields.isIncluded("snapshot-dependencies", false, true)) {
      return null;
    }
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("snapshot-dependencies", false),
      () -> Builds.createFromBuildPromotions(
        getBuildPromotions(myBuildPromotion.getDependencies()),
        myFields.getNestedField("snapshot-dependencies", Fields.NONE, Fields.LONG),
        myBeanContext
      )
    );
  }

  @XmlElement(name = "artifact-dependencies")
  public Builds getBuildArtifactDependencies() {
    if (myBuild == null) {
      //todo: support serving artifact dependencies for queued build, may be rename the node
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("artifact-dependencies", false), () -> {
      final Map<jetbrains.buildServer.Build, List<ArtifactInfo>> artifacts = myBuild.getDownloadedArtifacts().getArtifacts();
      final List<BuildPromotion> builds = new ArrayList<BuildPromotion>(artifacts.size());
      for (jetbrains.buildServer.Build sourceBuild : artifacts.keySet()) {
        //TeamCity API: cast to SBuild?
        builds.add(((SBuild)sourceBuild).getBuildPromotion());
      }
      Collections.sort(builds, new BuildPromotionDependenciesComparator());
      return Builds.createFromBuildPromotions(
        builds,
        myFields.getNestedField("artifact-dependencies", Fields.NONE, Fields.LONG),
        myBeanContext
      );
    });
  }

  /**
   * Specifies artifact dependencies to be used in the build _instead_ of those specified in the build type.
   */
  @XmlElement(name = "custom-artifact-dependencies")
  public PropEntitiesArtifactDep getCustomBuildArtifactDependencies() {
    if (myBuild != null) {
      //todo: support serving for the running/finished builds, via a link
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("custom-artifact-dependencies", false), () -> {
      final List<SArtifactDependency> artifactDependencies = ((BuildPromotionEx)myBuildPromotion).getCustomArtifactDependencies(); //TeamCity API: cast
      return new PropEntitiesArtifactDep(artifactDependencies, null, myFields.getNestedField("custom-artifact-dependencies", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  /**
   * Experimental support only
   */
  @XmlElement(name = "artifactDependencyChanges")
  public BuildChanges getArtifactDependencyChanges() {
    boolean isCached = false;
    if (myBuild != null) {
      isCached = myBeanContext.getSingletonService(DownloadedArtifactsLoggerImpl.class).hasComputedSourceBuilds(myBuild.getBuildId());
    }

    return ValueWithDefault.decideDefault(myFields.isIncluded("artifactDependencyChanges", isCached, false, false),
                                          () -> Build.getArtifactDependencyChangesNode(myBuildPromotion, myFields.getNestedField("artifactDependencyChanges"), myBeanContext));
  }

  /**
   * Experimental support only
   * This is meant to replicate the UI logic of calculating number of changes in a build. Returns the number of changes limited by the maximum number as calculated in UI.
   * Returns the limit+1 if there are more changes then the limit configured
   */
  @XmlAttribute(name = "limitedChangesCount")
  public Integer getLimitedChangesCount() {
    Supplier<Boolean> isCached = () -> ((BuildPromotionEx)myBuildPromotion).hasComputedChanges(SelectPrevBuildPolicy.SINCE_LAST_BUILD,
                                                 new LimitingVcsModificationProcessor(ChangesPopupUtil.getBuildChangesPopupLimit())); //see ChangesBean.lazyChanges;
    return ValueWithDefault.decideDefault(myFields.isIncludedFull("limitedChangesCount", isCached, false, false),
                                          () -> {
                                            ChangesBean changesBean = ChangesBean.createForChangesLink(myBuildPromotion, null);
                                            int result = changesBean.getTotal();
                                            if (changesBean.isChangesLimitExceeded()) result++;
                                            return result;
                                          });
  }

  /**
   * Experimental support only
   */
  @XmlAttribute(name = "artifactsDirectory")
  public String getArtifactsDirectory() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("artifactsDirectory", false, false),
                                                              () -> {
                                                                myBeanContext.getServiceLocator().findSingletonService(PermissionChecker.class)
                                                                             .checkGlobalPermission(Permission.VIEW_SERVER_SETTINGS);
                                                                return myBuildPromotion.getArtifactsDirectory().getAbsolutePath();
                                                              });
  }

  @XmlElement(name = "revisions")
  public Revisions getRevisions() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("revisions", false),
                                          () -> new Revisions(myBuildPromotion.getRevisions(), myFields.getNestedField("revisions", Fields.NONE, Fields.LONG), myBeanContext));
  }

  @XmlElement(name = "versionedSettingsRevision")
  public Revision getVersionedSettingsRevision() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("versionedSettingsRevision", false), () -> {
      List<BuildRevision> revisions =
        CollectionsUtil.filterAndConvertCollection(((BuildPromotionEx)myBuildPromotion).getAllRevisionsMap().values(), source -> source, data -> data.isSettingsRevision());
      if (revisions.isEmpty()) {
        return null;
      }
      if (revisions.size() > 1) {
        LOG.warn("Found more then one versioned settings revision for " + LogUtil.describe(myBuildPromotion));
      }
      return new Revision(revisions.get(0), myFields.getNestedField("versionedSettingsRevision", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  /**
   * Lists last change(s) included into the build so that this can be used in the build start request. The changes correspond to the revisions used by the build.
   * The set of the changes included can vary in the future TeamCity versions. In TeamCity 8.1 this is the last usual change and also a personal change (for personal build only)
   *
   * @return
   */
  @XmlElement(name = "lastChanges")
  public Changes getLastChanges() {
    if (!myFields.isIncluded("lastChanges", false, true)) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("lastChanges", false), () -> {
      final Changes result = Changes.fromSVcsModificationsSupplier(() -> {
        final List<SVcsModification> modifications = new ArrayList<SVcsModification>();
        final Long lastModificationId = myBuildPromotion.getLastModificationId();
        if (lastModificationId != null && lastModificationId != -1) {
          try {
            SVcsModification modification = myBeanContext.getSingletonService(VcsModificationHistory.class).findChangeById(lastModificationId);
            if (modification != null && modification.getRelatedConfigurations().contains(myBuildPromotion.getParentBuildType())) {
              modifications.add(modification);
            }
          } catch (AccessDeniedException e) {
            //ignore: the associated modification id probably does not belong to the build configuration (related to TW-35390)
          }
        }
        modifications.addAll(myBuildPromotion.getPersonalChanges());
        return modifications;
      }, null, myFields.getNestedField("lastChanges", Fields.NONE, Fields.LONG), myBeanContext);
      return result.isDefault() ? null : result;
    });
  }

  @XmlElement(name = "changes")
  public Changes getChanges() {
    if (!myFields.isIncluded("changes", false, true)) {
      return null;
    }
    return ValueWithDefault.decide(myFields.isIncluded("changes", false), () -> {
      final Fields changesFields = myFields.getNestedField("changes");
      String locator = Locator.merge(changesFields.getLocator(), ChangeFinder.getLocator(myBuildPromotion));
      final String href = ChangeRequest.getChangesHref(locator); //using locator without count in href
      final String finalLocator = Locator.merge(locator, Locator.getStringLocator(PagerData.COUNT, String.valueOf(FinderImpl.NO_COUNT)));
      CachingValue<List<SVcsModificationOrChangeDescriptor>> data;
      ChangeFinder changeFinder = myBeanContext.getSingletonService(ChangeFinder.class);
      try {
        if (changeFinder.isCheap(myBuildPromotion, finalLocator)) {
          data = CachingValue.simple(changeFinder.getItems(finalLocator).getEntries());
        } else {
          data = CachingValue.simple(() -> changeFinder.getItems(finalLocator).getEntries());
        }
      } catch (Exception e) {
        LOG.warnAndDebugDetails("Failed to get changes (including empty changes) for " + LogUtil.describe(myBuildPromotion), e);
        data = CachingValue.simple(() -> Collections.emptyList());
      }
      return new Changes(new PagerDataImpl(href), changesFields, myBeanContext, data);
    }, null, true);
    //see jetbrains.buildServer.controllers.changes.ChangesBean.getLimitedChanges for further optimization
  }

  @XmlElement(name = "triggered")
  public TriggeredBy getTriggered() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("triggered", false), () -> {
      jetbrains.buildServer.serverSide.TriggeredBy triggeredBy = null;
      if (myBuild != null) {
        triggeredBy = myBuild.getTriggeredBy();
      } else if (myQueuedBuild != null) {
        triggeredBy = myQueuedBuild.getTriggeredBy();
      }
      return triggeredBy == null ? null : new TriggeredBy(triggeredBy, myFields.getNestedField("triggered", Fields.NONE, Fields.LONG), myBeanContext);
    });
  }

  @XmlElement(name = "relatedIssues")
  public IssueUsages getIssues() {
    return myBuild == null
           ? null
           : ValueWithDefault.decideDefault(myFields.isIncluded("relatedIssues", false), () -> {
             final boolean includeAllInline = TeamCityProperties.getBoolean("rest.beans.build.inlineRelatedIssues");
             return new IssueUsages(myBuild, myFields.getNestedField("relatedIssues", Fields.NONE, includeAllInline ? Fields.LONG : Fields.SHORT), myBeanContext);
           });
  }

  @XmlElement(name = "user")
  public jetbrains.buildServer.server.rest.model.user.User getPersonalBuildUser() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("user", false), () -> {
      final SUser owner = myBuildPromotion.getOwner();
      return owner == null ? null : new User(owner, myFields.getNestedField("user"), myBeanContext);
    });
  }

  @XmlElement(name = "artifacts")
  public Files getArtifacts() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("artifacts", false), new ValueWithDefault.Value<Files>() {
      public Files get() {
        final Fields nestedFields = myFields.getNestedField("artifacts");
        final String childrenLocator = nestedFields.getLocator();
        final FileApiUrlBuilder builder = FilesSubResource.fileApiUrlBuilder(childrenLocator, BuildRequest.getBuildArtifactsHref(myBuildPromotion));
        return new Files(builder.getChildrenHref(null), new Files.DefaultFilesProvider(builder, myBeanContext) {
          @NotNull
          @Override
          protected List<? extends Element> getItems() {
            return BuildArtifactsFinder.getItems(BuildArtifactsFinder.getArtifactElement(myBuildPromotion, "", myServiceLocator), childrenLocator, builder, myServiceLocator);
          }

          @Override
          public int getCount() {
            if (myItems != null) {
              return myItems.size();
            }
            Integer cheapCount = getCheapCount();
            if (cheapCount != null) {
              return cheapCount;
            }
            return super.getCount();
          }

          @Override
          public boolean isCountCheap() {
            if (super.isCountCheap()) return true;
            return getCheapCount() != null;
          }

          private Integer myCachedCheapCount = null;
          private boolean myCheapCountIsCalculated = false;

          @Nullable
          private Integer getCheapCount() {
            if (!myCheapCountIsCalculated) {
              myCachedCheapCount = getCheapCountInternal();
              myCheapCountIsCalculated = true;
            }
            return myCachedCheapCount;
          }

          @Nullable
          private Integer getCheapCountInternal() {
            if (!((BuildPromotionEx)myBuildPromotion).hasComputedArtifactsState()) return null;

            // optimize response by using cached artifacts presence
            BuildPromotionEx.ArtifactsState state = ((BuildPromotionEx)myBuildPromotion).getArtifactStateInfo().getState();
            if (state == BuildPromotionEx.ArtifactsState.NO_ARTIFACTS) return 0;

            Integer requestedCount = BuildArtifactsFinder.getCountIfDefaultLocator(childrenLocator);
            if (requestedCount == null) return null; //not default locator
            if (state == BuildPromotionEx.ArtifactsState.HIDDEN_ONLY) return 0;
            if (requestedCount == 1 && state == BuildPromotionEx.ArtifactsState.HAS_ARTIFACTS) return 1;

            return null;
          }
        }, nestedFields, myBeanContext);
      }
    });
  }

  @XmlElement(name = "downloadedArtifacts")
  public DownloadedArtifacts getDownloadedArtifacts() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("downloadedArtifacts", false, false),
      () -> {
        if(myBuild == null) {
          return null;
        }

        Fields nested = myFields.getNestedField("downloadedArtifacts");
        return new DownloadedArtifacts(myBuild.getDownloadedArtifacts(), nested, myBeanContext);
      }
    );
  }

  @XmlElement(name = "testOccurrences")
  public TestOccurrences getTestOccurrences() {
    if (myBuild == null) return null;
    return ValueWithDefault.decideDefault(myFields.isIncluded("testOccurrences", false),
                                          () -> {
                                            final Fields testOccurrencesFields = myFields.getNestedField("testOccurrences");
                                            final Boolean testDetailsIncluded = TestOccurrences.isTestOccurrenceIncluded(testOccurrencesFields);
                                            final BuildStatistics fullStatistics = (testDetailsIncluded == null || testDetailsIncluded) ?
                                                                                   TestOccurrenceFinder.getBuildStatistics(myBuild, null) : null;
                                            final ShortStatistics statistics = fullStatistics != null ? fullStatistics : myBuild.getShortStatistics();
                                            if (statistics.getAllTestCount() == 0) {
                                              return null;
                                            }

                                            if (myBuild.getBuildType() == null) {
                                              //workaround for http://youtrack.jetbrains.com/issue/TW-34734
                                              return null;
                                            }
                                            final List<STestRun> tests = ValueWithDefault.decideDefault(
                                              testDetailsIncluded, () -> {
                                                String testOccurrencesLocator = testOccurrencesFields.getLocator();
                                                if (testOccurrencesLocator == null) {
                                                  return fullStatistics != null
                                                         ? fullStatistics.getAllTests()
                                                         : TestOccurrenceFinder.getBuildStatistics(myBuild, null).getAllTests();
                                                }
                                                String actualLocatorText = Locator.merge(TestOccurrenceFinder.getTestRunLocator(myBuild), testOccurrencesLocator);
                                                return myServiceLocator.getSingletonService(TestOccurrenceFinder.class).getItems(actualLocatorText).getEntries();
                                              }
                                            );
                                            return new TestOccurrences(tests, statistics, TestOccurrenceRequest.getHref(myBuild), null, testOccurrencesFields, myBeanContext);
                                          });
  }

  @XmlElement(name = "problemOccurrences")
  public ProblemOccurrences getProblemOccurrences() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("problemOccurrences", false),
                                          () -> {
                                            final List<BuildProblem> problemOccurrences = ProblemOccurrenceFinder.getProblemOccurrences(myBuildPromotion);
                                            if (problemOccurrences.size() == 0) return null;

                                            int newProblemsCount = 0;
                                            int mutedProblemsCount = 0;
                                            for (BuildProblem problem : problemOccurrences) {
                                              if (problem.isMutedInBuild()) mutedProblemsCount++;
                                              final Boolean isNew = ((BuildProblemImpl)problem).isNew();
                                              if (isNew != null && isNew) newProblemsCount++;
                                            }
                                            final Fields problemOccurrencesFields = myFields.getNestedField("problemOccurrences");
                                            final List<BuildProblem> problems = ValueWithDefault.decideDefault(problemOccurrencesFields.isIncluded("problemOccurrence", false),
                                                                                                               problemOccurrences);
                                            return new ProblemOccurrences(problems,
                                                                          problemOccurrences.size(),
                                                                          null,
                                                                          null,
                                                                          newProblemsCount,
                                                                          null,
                                                                          mutedProblemsCount,
                                                                          ProblemOccurrenceRequest.getHref(myBuildPromotion),
                                                                          null,
                                                                          problemOccurrencesFields, myBeanContext
                                            );
                                          });
  }

  static List<SBuild> getBuilds(@NotNull Collection<? extends BuildDependency> dependencies) {
    List<SBuild> result = new ArrayList<SBuild>(dependencies.size());
    for (BuildDependency dependency : dependencies) {
      final SBuild dependOnBuild = dependency.getDependOn().getAssociatedBuild();
      if (dependOnBuild != null) {
        result.add(dependOnBuild);
      }
    }
    Collections.sort(result, new BuildDependenciesComparator());
    return result;
  }

  static List<BuildPromotion> getBuildPromotions(@NotNull Collection<? extends BuildDependency> dependencies) {
    List<BuildPromotion> result = new ArrayList<BuildPromotion>(dependencies.size());
    for (BuildDependency dependency : dependencies) {
      result.add(dependency.getDependOn());
    }
    Collections.sort(result, new BuildPromotionDependenciesComparator());
    return result;
  }

  private static class BuildDependenciesComparator implements Comparator<SBuild> {
    public int compare(final SBuild o1, final SBuild o2) {
      final int buildTypesCompare = o1.getBuildTypeId().compareTo(o2.getBuildTypeId());
      return buildTypesCompare != 0 ? buildTypesCompare : (int)(o1.getBuildId() - o2.getBuildId());
    }
  }

  public static class BuildPromotionDependenciesComparator implements Comparator<BuildPromotion> {
    public int compare(final BuildPromotion o1, final BuildPromotion o2) {
      final int buildTypesCompare = o1.getBuildTypeId().compareTo(o2.getBuildTypeId());
      return buildTypesCompare != 0 ? buildTypesCompare : (int)(o1.getId() - o2.getId());
    }
  }

  @XmlElement(name = CANCELED_INFO)
  public Comment getCanceledInfo() {  //TeamCity API: is only available for running or finished build, while isCanceled is available for queued
    return myBuild == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded(CANCELED_INFO, false),
                                                                   () -> getCanceledComment(myBuild, myFields.getNestedField(CANCELED_INFO, Fields.NONE, Fields.LONG), myBeanContext));
  }


  @XmlElement
  public String getQueuedDate() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("queuedDate", false), () -> {
      Date result = null;
      if (myBuild != null) {
        result = myBuild.getQueuedDate();
      } else if (myQueuedBuild != null) {
        result = myQueuedBuild.getWhenQueued();
      }
      return result == null ? null : Util.formatTime(result);
    });
  }

  @XmlElement(name = "compatibleAgents")
  public Agents getCompatibleAgents() {
    if (myQueuedBuild == null) {
      return null;
    }

    return ValueWithDefault.decideDefault(myFields.isIncluded("compatibleAgents", false, true), () -> {
      Fields nestedFields = myFields.getNestedField("compatibleAgents");
      String actualLocatorText = Locator.merge(nestedFields.getLocator(), AgentFinder.getCompatibleAgentsLocator(myBuildPromotion));

      return new Agents(actualLocatorText, new PagerDataImpl(AgentRequest.getItemsHref(actualLocatorText)), nestedFields, myBeanContext);
    });
  }

  @XmlElement
  public String getStartEstimate() {
    final Boolean include = myFields.isIncluded("startEstimate", false);
    if (myQueuedBuild == null || (include != null && !include)) return null;
    return ValueWithDefault.decideDefault(include, () -> {
      final BuildEstimates buildEstimates = myQueuedBuild.getBuildEstimates();
      if (buildEstimates == null) return null;

      final TimeInterval timeInterval = buildEstimates.getTimeInterval();
      if (timeInterval == null) return null;

      if (TeamCityProperties.getBoolean("rest.beans.build.startEstimate.legacyBehavior")) {
        //logic before https://youtrack.jetbrains.com/issue/TW-50824 fix as the fix goes to the bugfix update
        //this property support can be dropped in TeamCity 2017.2
        final TimePoint endPoint = timeInterval.getEndPoint();
        if (endPoint == null) return null;
        return Util.formatTime(endPoint.getAbsoluteTime());
      }
      TimePoint result = timeInterval.getStartPoint();
      if (result == TimePoint.NEVER) return null;
      return Util.formatTime(result.getAbsoluteTime());
    });
  }

  @XmlElement
  public String getFinishEstimate() {
    if(myQueuedBuild == null) {
      return null;
    }

    return ValueWithDefault.decideDefault(
      myFields.isIncluded(FINISH_ESTIMATE, false),
      () -> {
        BuildEstimates estimates = myQueuedBuild.getBuildEstimates();
        if(estimates == null) return null;

        TimeInterval estimateInterval = estimates.getTimeInterval();
        if(estimateInterval == null) return null;

        TimePoint endPoint = estimateInterval.getEndPoint();
        if(TimePoint.NEVER.equals(endPoint) || endPoint == null) {
          return null;
        }

        return Util.formatTime(endPoint.getAbsoluteTime());
      }
    );
  }


  @XmlElement(name = "waitReason")
  public String getWaitReason() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("waitReason", false), () -> {
      if(myQueuedBuild == null) return null;

      if(myQueuedBuild instanceof QueuedBuildEx) {
        return ((QueuedBuildEx) myQueuedBuild).getLatestWaitReason();
      }

      // Fallback to old way of doing things.
      final BuildEstimates buildEstimates = myQueuedBuild.getBuildEstimates();
      if (buildEstimates == null) return null;

      final WaitReason waitReason = buildEstimates.getWaitReason();
      if (waitReason == null) return null;
      return waitReason.getDescription();
    });
  }

  @XmlElement(name = "queuedWaitReasons")
  public Properties getQueuedWaitReasons() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("queuedWaitReasons", false, false),
      () -> {
        if(myQueuedBuild != null) {
          return getQueuedWaitReasonsForQueuedBuild();
        }

        return getQueuedWaitReasonsForRunningOrFinishedBuild();
      }
    );
  }

  @Nullable
  private Properties getQueuedWaitReasonsForQueuedBuild() {
    if(!(myQueuedBuild instanceof QueuedBuildEx)) return null;

    Map<String, String> reasons = ((QueuedBuildEx) myQueuedBuild).getWaitReasons().entrySet().stream()
                                                                 .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().toString()));

    return new Properties(reasons, null, myFields.getNestedField("queuedWaitReasons"), myBeanContext);
  }

  @Nullable
  private Properties getQueuedWaitReasonsForRunningOrFinishedBuild() {
    if(myBuild == null) return null;

    final Fields nestedField = myFields.getNestedField("queuedWaitReasons");
    if(!nestedField.isMoreThenShort()) {
      return null;
    }

    Map<String, String> reasons = new HashMap<>();
    myBuild.getWaitReasons().forEach((name, val) -> reasons.put(name, Long.toString(val)));
    return new Properties(reasons, null, nestedField, myBeanContext);
  }

  @XmlElement(name = "delayedByBuild")
  public Build getDelayedByBuild() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("delayedByBuild", false, false),
      () -> {
        SBuildAgent agent = getEstimatedAgent();
        if(agent == null) {
          return null;
        }

        SRunningBuild delayer = agent.getRunningBuild();
        if(delayer == null) {
          return null;
        }

        // workaround for TW-77050, the same at Agent.build
        try {
          myBeanContext.getSingletonService(PermissionChecker.class).checkPermission(Permission.VIEW_PROJECT, delayer.getBuildPromotion());

          return new Build(delayer, myFields.getNestedField("delayedByBuild"), myBeanContext);
        } catch (AuthorizationFailedException e) {
          return Build.getNoPermissionsBuild(delayer, myFields.getNestedField("delayedByBuild"), myBeanContext);
        }
      }
    );
  }

  @XmlElement(name = "plannedAgent")
  public Agent getPlannedAgent() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("plannedAgent", false, false),
      () -> {
        SBuildAgent agent = getEstimatedAgent();
        if(agent == null) {
          return null;
        }

        return new Agent(agent, myFields.getNestedField("plannedAgent"), myBeanContext);
      }
    );
  }

  @Nullable
  private SBuildAgent getEstimatedAgent() {
    if(myQueuedBuild == null || myQueuedBuild.getBuildPromotion().isCompositeBuild()) {
      return null;
    }

    BuildEstimates estimates = myQueuedBuild.getBuildEstimates();
    if(estimates == null) {
      return null;
    }

    BuildAgent agent = estimates.getAgent();
    if(agent == null || !(agent instanceof SBuildAgent) || (agent instanceof BuildAgentEx && ((BuildAgentEx) agent).isFakeAgent())) {
      return null;
    }

    return (SBuildAgent) agent;
  }

  /**
   * Experimental
   * Can be "0" if the build is being started already
   * Note that the number can be inconsistent between several builds (e.g. several builds can have the same position in the queue) as it represent the momentary position which change even within the single request's time
   */
  @XmlAttribute
  public Integer getQueuePosition() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("queuePosition", false, false),
                                          () -> myQueuedBuild == null ? null : myQueuedBuild.getOrderNumber());
  }

  /**
   * Experimental
   */
  @XmlElement
  public String getSettingsHash() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("settingsHash", false, false), () -> {
      checkCanViewRuntimeData();
      return new String(Hex.encodeHex(((BuildPromotionEx)myBuildPromotion).getSettingsDigest(false)));
    });
  }

  /**
   * Experimental
   */
  @XmlElement
  public String getCurrentSettingsHash() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("currentSettingsHash", false, false), () -> {
      checkCanViewRuntimeData();
      return new String(Hex.encodeHex(((BuildPromotionEx)myBuildPromotion).getBuildSettings().getDigest()));
    });
  }

  /**
   * Experimental
   */
  @XmlElement
  public String getModificationId() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("modificationId", false, false), () -> {
      checkCanViewRuntimeData();
      return String.valueOf(myBuildPromotion.getLastModificationId());
    });
  }

  /**
   * Experimental
   */
  @XmlElement
  public String getChainModificationId() {
    return ValueWithDefault.decideDefaultIgnoringAccessDenied(myFields.isIncluded("chainModificationId", false, false), () -> {
      checkCanViewRuntimeData();
      return String.valueOf(((BuildPromotionEx)myBuildPromotion).getChainModificationId());
    });
  }

  /**
   * Experimental
   */
  @XmlElement
  public Items getReplacementIds() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("replacementIds", false, false), () -> {
      final Collection<Long> replacementIds = myServiceLocator.getSingletonService(BuildPromotionReplacement.class).getOriginalPromotionIds(myBuildPromotion.getId());
      ArrayList<Long> sortedReplacemetIds = new ArrayList<>(replacementIds);
      Collections.sort(sortedReplacemetIds, Collections.reverseOrder());

      return new Items(CollectionsUtil.convertCollection(sortedReplacemetIds, source -> String.valueOf(source)));
    });
  }

  @Nullable
  @XmlAttribute
  public Boolean isDetachedFromAgent() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("detachedFromAgent"), () -> myBuild != null && myBuild.isDetachedFromAgent());
  }

  @Nullable
  @XmlElement(name = "finishOnAgentDate")
  public String getFinishOnAgentDate() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("finishOnAgentDate"), () -> myBuild != null ? Util.formatTime(myBuild.getFinishOnAgentDate()) : null);
  }

  @Nullable
  @XmlAttribute
  public Boolean isCustomized() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("customized"), () -> ((BuildPromotionEx)myBuildPromotion).getPromotionCustomization().isCustomized());
  }

  @XmlElement
  public Customizations getCustomization() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("customization", false, true), () -> {
      return new Customizations(((BuildPromotionEx)myBuildPromotion).getPromotionCustomization().getItems());
    });
  }

  /*
   * Experimental, since 2021.2
   */
  @Nullable
  @XmlAttribute
  public Boolean isChangesCollectingInProgress() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("changesCollectingInProgress", false, false),
      ((BuildPromotionEx)myBuildPromotion).isChangesCollectingInProgress()
    );
  }

  private boolean myCanViewRuntimeDataChecked = false;

  private void checkCanViewRuntimeData() {
    if (!myCanViewRuntimeDataChecked) {
      //noinspection ConstantConditions
      myBeanContext.getServiceLocator().findSingletonService(PermissionChecker.class).checkPermission(Permission.VIEW_BUILD_RUNTIME_DATA, myBuildPromotion);
      myCanViewRuntimeDataChecked = true;
    }
  }

  public static boolean canViewRuntimeData(@NotNull PermissionChecker permissionChecker, @NotNull BuildPromotion buildPromotion) {
    final SBuildType buildType = buildPromotion.getBuildType();
    final AuthorityHolder authorityHolder = permissionChecker.getCurrent();
    if (buildType == null) {
      return authorityHolder.isPermissionGrantedGlobally(Permission.VIEW_BUILD_RUNTIME_DATA);
    }
    return authorityHolder.isPermissionGrantedForProject(buildType.getProjectId(), Permission.VIEW_BUILD_RUNTIME_DATA);
  }

  /**
   * Experimental
   */
  @XmlElement
  public Related getRelated() { //todo: try to use something like RelatedEntities here?
    return ValueWithDefault.decideDefault(myFields.isIncluded("related", false, false),
                                          () -> {
                                            Fields nestedField = myFields.getNestedField("related", Fields.LONG, Fields.LONG);
                                            return new Related(nestedField.withContext(myBuildPromotion), myBeanContext);
                                          });
  }

  @ModelExperimental
  @XmlAttribute(name = "parallelized")
  public Boolean isParallelized() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("parallelized", false, false),
      () -> SplitBuildsFeatureUtil.isParallelizedBuild(myBuildPromotion)
    );
  }

  public static Comment getCanceledComment(@NotNull final SBuild build, @NotNull final Fields fields, @NotNull final BeanContext context) {
    final CanceledInfo canceledInfo = build.getCanceledInfo();
    if (canceledInfo == null) return null;

    jetbrains.buildServer.users.User user = null;
    if (canceledInfo.isCanceledByUser()) {
      final Long userId = canceledInfo.getUserId();
      assert userId != null;
      user = context.getSingletonService(UserModel.class).findUserById(userId);
    }
    return new Comment(user, new Date(canceledInfo.getCreatedAt()), canceledInfo.getComment(), fields, context);  //todo: returns wrong date after server restart!
  }

  /**
   * This is used only when posting a link to the build
   */
  private Long submittedId;
  private Long submittedPromotionId;
  private String submittedLocator;

  public void setId(Long id) {
    submittedId = id;
  }

  public void setPromotionId(Long id) {
    submittedPromotionId = id;
  }

  @Nullable
  public Long getPromotionIdOfSubmittedBuild() {
    return submittedPromotionId != null ? submittedPromotionId : submittedId;
  }

  /**
   * This is used only when posting a link to the build
   */
  @XmlAttribute
  public String getLocator() {
    return null;
  }

  public void setLocator(final String locator) {
    submittedLocator = locator;
  }

  @NotNull
  public BuildPromotion getFromPosted(@NotNull final BuildPromotionFinder buildFinder,
                                      @NotNull final Map<Long, Long> buildPromotionIdQueuedBuildsReplacements) {
    return buildFinder.getItem(getLocatorFromPosted(buildPromotionIdQueuedBuildsReplacements));
  }

  @NotNull
  public String getLocatorFromPosted(final @NotNull Map<Long, Long> buildPromotionIdQueuedBuildsReplacements) {
    String locatorText;
    if (submittedLocator != null) {
      if (submittedPromotionId != null) {
        throw new BadRequestException("Both 'locator' and '" + BuildPromotionFinder.PROMOTION_ID + "' attributes are specified. Only one should be present.");
      }
      if (submittedId != null) {
        throw new BadRequestException("Both 'locator' and '" + BuildPromotionFinder.DIMENSION_ID + "' attributes are specified. Only one should be present.");
      }
      locatorText = submittedLocator;
    } else {
      final Locator locator = Locator.createEmptyLocator();
      if (submittedPromotionId != null) {
        final Long replacementPromotionId = buildPromotionIdQueuedBuildsReplacements.get(submittedPromotionId);
        if (replacementPromotionId != null) {
          locator.setDimension(BuildPromotionFinder.PROMOTION_ID, String.valueOf(replacementPromotionId));
        } else {
          locator.setDimension(BuildPromotionFinder.PROMOTION_ID, String.valueOf(submittedPromotionId));
        }
      }
      if (submittedId != null) {
        //assuming https://youtrack.jetbrains.com/issue/TW-38777 never takes place
        final Long replacementPromotionId = buildPromotionIdQueuedBuildsReplacements.get(submittedId);
        if (replacementPromotionId != null) {
          locator.setDimension(BuildPromotionFinder.PROMOTION_ID, String.valueOf(replacementPromotionId));
        } else {
          locator.setDimension(BuildPromotionFinder.DIMENSION_ID, String.valueOf(submittedId));
        }
      }
      if (locator.isEmpty()) {
        throw new BadRequestException("No build specified. Either '" + BuildPromotionFinder.DIMENSION_ID + "' or 'locator' attributes should be present.");
      }

      locatorText = locator.getStringRepresentation();
    }

    return locatorText;
  }

  private BuildTriggeringOptions submittedTriggeringOptions;
  private String submittedBuildTypeId;
  private BuildType submittedBuildType;
  private Comment submittedComment;
  private Properties submittedProperties;
  private String submittedBranchName;
  private Boolean submittedPersonal;
  private Changes submittedLastChanges;
  private Revisions submittedRevisions;
  private Builds submittedBuildDependencies;
  private Agent submittedAgent;
  //todo: add support for snapshot dependency options, probably with: private PropEntitiesSnapshotDep submittedCustomBuildSnapshotDependencies;
  private PropEntitiesArtifactDep submittedCustomBuildArtifactDependencies;
  private Builds submittedBuildArtifactDependencies;
  private Tags submittedTags;
  private Entries submittedAttributes;
  private TriggeredBy submittedTriggeredBy;

  /**
   * Used only when posting for triggering a build
   *
   * @return
   */
  @XmlElement
  public BuildTriggeringOptions getTriggeringOptions() {
    return null;
  }

  public void setTriggeringOptions(final BuildTriggeringOptions submittedTriggeringOptions) {
    this.submittedTriggeringOptions = submittedTriggeringOptions;
  }

  public void setBuildTypeId(final String submittedBuildTypeId) {
    this.submittedBuildTypeId = submittedBuildTypeId;
  }

  public void setBuildType(final BuildType submittedBuildType) {
    this.submittedBuildType = submittedBuildType;
  }

  public void setComment(final Comment submittedComment) {
    this.submittedComment = submittedComment;
  }

  public void setProperties(final Properties submittedProperties) {
    this.submittedProperties = submittedProperties;
  }

  public void setBranchName(final String submittedBranchName) {
    this.submittedBranchName = submittedBranchName;
  }

  public void setPersonal(final Boolean submittedPersonal) {
    this.submittedPersonal = submittedPersonal;
  }

  public void setLastChanges(final Changes submittedLstChanges) {
    this.submittedLastChanges = submittedLstChanges;
  }

  public void setRevisions(Revisions submittedRevisions) {
    this.submittedRevisions = submittedRevisions;
  }

  public void setBuildDependencies(final Builds submittedBuildDependencies) {
    this.submittedBuildDependencies = submittedBuildDependencies;
  }

  public void setAgent(final Agent submittedAgent) {
    this.submittedAgent = submittedAgent;
  }

  public void setCustomBuildArtifactDependencies(final PropEntitiesArtifactDep submittedCustomBuildArtifactDependencies) {
    this.submittedCustomBuildArtifactDependencies = submittedCustomBuildArtifactDependencies;
  }

  public void setBuildArtifactDependencies(final Builds submittedBuildArtifactDependencies) {
    this.submittedBuildArtifactDependencies = submittedBuildArtifactDependencies;
  }

  public void setAttributes(final Entries submittedAttributes) {
    this.submittedAttributes = submittedAttributes;
  }

  public void setTags(final Tags tags) {
    this.submittedTags = tags;
  }

  public void setTriggered(final TriggeredBy triggeredBy) {
    submittedTriggeredBy = triggeredBy;
  }

  private BuildPromotion getBuildToTrigger(@Nullable final SUser user, @NotNull final ServiceLocator serviceLocator, @NotNull final Map<Long, Long> buildPromotionIdReplacements) {
    List<BuildPromotion> customDependencies = Collections.emptyList();
    if (submittedBuildDependencies != null) {
      customDependencies = submittedBuildDependencies.getFromPosted(serviceLocator, buildPromotionIdReplacements);
    }

    SVcsModification changeToUse = null;
    SVcsModification personalChangeToUse = null;
    if (submittedLastChanges != null) {
      List<SVcsModification> lastChanges = submittedLastChanges.getChangesFromPosted(serviceLocator.getSingletonService(ChangeFinder.class));
      if (lastChanges.size() > 0) {
        boolean changeProcessed = false;
        boolean personalChangeProcessed = false;
        for (SVcsModification change : lastChanges) {
          if (!change.isPersonal()) {
            if (!changeProcessed) {
              changeToUse = change;
              changeProcessed = true;
            } else {
              throw new BadRequestException("Several non-personal changes are submitted, only one can be present");
            }
          } else {
            if (!personalChangeProcessed) {
              personalChangeToUse = change;
              personalChangeProcessed = true;
            } else {
              throw new BadRequestException("Several personal changes are submitted, only one can be present");
            }
          }
        }
      }
    }

    final SBuildType submittedBuildType = getSubmittedBuildType(serviceLocator, personalChangeToUse, user);
    final BuildCustomizerEx customizer = (BuildCustomizerEx)serviceLocator.getSingletonService(BuildCustomizerFactory.class).createBuildCustomizer(submittedBuildType, user);
    if (changeToUse != null) {
      customizer.setChangesUpTo(changeToUse); //might need to rework after comparison to code in jetbrains.buildServer.controllers.RunBuildBean.setupBuildCustomizer: customizer.setNodeRevisions, etc.
    } else if (submittedRevisions != null) {
      try {
        setupRevisionsInCustomizer(customizer, submittedBuildType, submittedRevisions, customDependencies);
      } catch (RevisionsNotFoundException e) {
        StringBuilder missingRevisionsError = new StringBuilder("Missing revisions for the following build configurations and VCS roots:");
        e.getMissingRevisionsMap().forEach((bt, roots) -> {
          missingRevisionsError.append("\n");
          missingRevisionsError.append("build configuration: ").append(bt.getExternalId()).append(", VCS roots: ");
          missingRevisionsError.append(StringUtil.join(",", roots.stream().map(r -> r.getParent().getExternalId()).collect(Collectors.toList())));
        });

        missingRevisionsError.append("\n(add attribute failOnMissingRevisions=false to the revisions element to disable this check)");

        throw new BadRequestException(missingRevisionsError.toString());
      }
    }

    if (submittedComment != null) {
      if (submittedComment.text != null) {
        customizer.setBuildComment(submittedComment.text);
      } else {
        throw new BadRequestException("Submitted comment does not have 'text' set.");
      }
    }
    if (submittedProperties != null) {
      customizer.setParameters(submittedProperties.getMap());
    }

    if (submittedBranchName != null) customizer.setDesiredBranchName(submittedBranchName); //this should ideally be used only when defaultBranc flag is not set. If set to false, should use customizer.setDesiredBranchName(submittedBranchName, false)
    if (submittedPersonal != null) customizer.setPersonal(submittedPersonal);

    if (!customDependencies.isEmpty()) {
      try {
        customizer.setSnapshotDependencyNodes(customDependencies);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Error trying to use specified snapshot dependencies" + getRelatedBuildDescription() + ":" + e.getMessage());
      } catch (NotFoundException e) {
        throw new BadRequestException("Error searching for snapshot dependency" + getRelatedBuildDescription() + ": " + e.getMessage(), e);
      }
    }

    if (submittedTriggeringOptions != null) {
      if (submittedTriggeringOptions.cleanSources != null) {
        customizer.setCleanSources(submittedTriggeringOptions.cleanSources);
      }
      if (submittedTriggeringOptions.cleanSourcesInAllDependencies != null) {
        customizer.setApplyCleanSourcesToDependencies(submittedTriggeringOptions.cleanSourcesInAllDependencies);
      }
      if (submittedTriggeringOptions.freezeSettings != null) {
        customizer.setFreezeSettings(submittedTriggeringOptions.freezeSettings);
      }
      if (submittedTriggeringOptions.tagDependencies != null) {
        customizer.setApplyTagsToDependencies(submittedTriggeringOptions.tagDependencies);
      }
      if (submittedTriggeringOptions.rebuildAllDependencies != null) {
        customizer.setRebuildDependencies(submittedTriggeringOptions.rebuildAllDependencies);
      }
      if (submittedTriggeringOptions.rebuildFailedOrIncompleteDependencies != null && submittedTriggeringOptions.rebuildFailedOrIncompleteDependencies) {
        customizer.setRebuildDependencies(BuildCustomizerEx.RebuildDependenciesMode.FAILED_OR_INCOMPLETE);
      }
      if (submittedTriggeringOptions.rebuildDependencies != null) {
        customizer.setRebuildDependencies(CollectionsUtil.convertCollection(
          submittedTriggeringOptions.rebuildDependencies.getFromPosted(serviceLocator.getSingletonService(BuildTypeFinder.class)), source -> {
              if (source.getBuildType() == null) {
                //noinspection ConstantConditions
                throw new BadRequestException("Template is specified instead of a build type. Template id: '" + source.getTemplate().getExternalId() + "'");
              }
              return source.getBuildType().getInternalId();
          }));
      }
    }

    List<BuildPromotion> artifactDepsBuildsPosted;
    try {
      artifactDepsBuildsPosted = submittedBuildArtifactDependencies == null ? null : submittedBuildArtifactDependencies.getFromPosted(serviceLocator, buildPromotionIdReplacements);
    } catch (NotFoundException e) {
      throw new BadRequestException("Error searching for artifact dependency" + getRelatedBuildDescription() + ": " + e.getMessage(), e);
    }
    if (submittedCustomBuildArtifactDependencies != null) {
      //todo: investigate if OK: here new dependencies are created and set into the build. Custom run build dialog onthe contrary, sets artifact deps with the same IDs into the build
      final List<SArtifactDependency> customDeps = submittedCustomBuildArtifactDependencies.getFromPosted(submittedBuildType.getArtifactDependencies(), serviceLocator);
      if (artifactDepsBuildsPosted == null) {
        setDepsWithNullCheck(customizer, customDeps);
      } else {
        //patch with "artifact-dependencies"
        setDepsWithNullCheck(customizer, getBuildPatchedDeps(customDeps, true, serviceLocator, artifactDepsBuildsPosted));
      }
    } else {
      if (artifactDepsBuildsPosted != null) {
        //use "artifact-dependencies" as the only dependencies as this allows to repeat a build
        setDepsWithNullCheck(customizer, getBuildPatchedDeps(submittedBuildType.getArtifactDependencies(), false, serviceLocator, artifactDepsBuildsPosted));
      } else {
        //no artifact dependencies customizations necessary
      }
    }

    if (submittedTags != null) {
      customizer.setTagDatas(new HashSet<TagData>(submittedTags.getFromPosted(serviceLocator.getSingletonService(UserFinder.class))));
    }
    if (submittedAttributes != null) {
      customizer.setAttributes(submittedAttributes.getMap());
    }
    final BuildPromotion result;
    try {
      result = customizer.createPromotion();
    } catch (IllegalStateException e) {
      //IllegalStateException is thrown e.g. when we try to create a personal build in a build type which does not allow this
      throw new BadRequestException("Cannot trigger build: " + e.getMessage());
    } catch (RevisionsNotFoundException e) {
      throw new BadRequestException(
        "Cannot trigger build, if the changes are specified, they should be visible on the build configuration Change Log under the requested branch. Original error: " +
        e.getMessage());
    }
    BuildTypeEx modifiedBuildType = getCustomizedSubmittedBuildType(serviceLocator);
    if (modifiedBuildType != null) {
      //it's core's responsibility to check permissions here
      try {
        ((BuildPromotionEx)result).freezeSettings(modifiedBuildType, "rest");
      } catch (IOException e) {
        throw new OperationException("Error while freezing promotion settings", e); //include nested erorr or it can expose too much data?
      }
    }
    return result;
  }

  private void setupRevisionsInCustomizer(@NotNull BuildCustomizerEx customizer,
                                          @NotNull SBuildType topBuildType,
                                          @NotNull Revisions submittedRevisions,
                                          @NotNull List<BuildPromotion> customDependencies) {
    Set<String> customizedDependencies = customDependencies.stream().map(p -> p.getParentBuildTypeId()).collect(Collectors.toSet());
    boolean failOnMissingRevisions = submittedRevisions.failOnMissingRevisions == null || Boolean.TRUE.equals(submittedRevisions.failOnMissingRevisions);

    List<BuildTypeEx> buildTypes = new ArrayList<>();
    buildTypes.add((BuildTypeEx)topBuildType);
    if (TeamCityProperties.getBooleanOrTrue("rest.triggerBuild.applyCustomRevisionsToDependencies")) {
      ((BuildTypeEx)topBuildType).traverseDependencies(bt -> {
        buildTypes.add(bt);
        return DependencyConsumer.Result.CONTINUE;
      });
    }

    for (BuildTypeEx bt: buildTypes) {
      if (customizedDependencies.contains(bt.getBuildTypeId())) continue;

      List<BuildRevisionEx> buildRevisions;
      try {
        buildRevisions = transformToBuildRevisions(bt, submittedRevisions);
      } catch (RevisionsNotFoundException e) {
        if (failOnMissingRevisions) throw e;
        continue;
      }

      long modId = -1;
      for (BuildRevisionEx r: buildRevisions) {
        final Long revModId = r.getModificationId();
        modId = Math.max(modId, revModId == null ? -1 : revModId);
      }
      customizer.setNodeRevisions(bt.getBuildTypeId(), modId, modId, buildRevisions);
    }
  }

  @NotNull
  private List<BuildRevisionEx> transformToBuildRevisions(@NotNull SBuildType buildType, @NotNull Revisions submittedRevisions) {
    VcsRootInstanceEntry implicitSettingsRootEntry = null;
    Map<Long, VcsRootInstanceEntry> vcsRootsMap = new HashMap<>();
    Map<String, VcsRootInstanceEntry> vcsRootsExtIdsMap = new HashMap<>();
    final List<VcsRootInstanceEntry> btRootInstances = ((BuildTypeEx)buildType).getVcsRootInstanceEntries(true);
    for (VcsRootInstanceEntry e: btRootInstances) {
      vcsRootsMap.put(e.getVcsRoot().getId(), e);
      vcsRootsExtIdsMap.put(e.getVcsRoot().getParent().getExternalId(), e);

      if (!buildType.containsVcsRoot(e.getVcsRoot().getParentId())) {
        implicitSettingsRootEntry = e;
      }
    }

    Map<VcsRootInstanceEntry, Revision> rootEntryToRevisionMap = new HashMap<>();
    for (Revision r: submittedRevisions.revisions) {
      VcsRootInstanceEntry rootEntry = findVcsRootEntry(vcsRootsMap, vcsRootsExtIdsMap, r);
      if (rootEntry == null) continue;

      if (r.checkoutRules != null) {
        CheckoutRules submittedCheckoutRules = new CheckoutRules(r.checkoutRules);
        // if submitted revisions have checkout rules specified, then we should compare them with expected checkout rules and apply submitted revisions only if they are the same
        if (!sameCheckoutRules(submittedCheckoutRules, rootEntry.getCheckoutRules())) {
          Revision existing = rootEntryToRevisionMap.get(rootEntry);
          if (existing != null && existing.checkoutRules == null) {
            // we had a revision in the map which was set there without a checkout rules verification, we should remove this entry
            // because there is a revision for the same entry but with checkout rules and for it the verification was not successful
            rootEntryToRevisionMap.remove(rootEntry);
          }
          continue;
        }
      }

      if (r.checkoutRules != null) {
        // a revision for which checkout rules verification passed has a higher priority than a revision without checkout rules
        rootEntryToRevisionMap.put(rootEntry, r);
      } else {
        rootEntryToRevisionMap.putIfAbsent(rootEntry, r);
      }
    }

    Map<Long, BuildRevisionEx> revisionsMap = new HashMap<>();
    for (Map.Entry<VcsRootInstanceEntry, Revision> e: rootEntryToRevisionMap.entrySet()) {
      revisionsMap.put(e.getKey().getVcsRoot().getId(), createBuildRevision(e.getValue(), e.getKey(), implicitSettingsRootEntry));
    }

    // check all revisions are set
    List<VcsRootInstance> missing = new ArrayList<>();
    for (VcsRootInstanceEntry re: btRootInstances) {
      // for custom builds we do not require presence of the settings revision for the following reasons:
      // 1) we assume that build settings are customized too
      // 2) if settings revision is not specified then the settings will be taken/frozen from the current build configuration settings
      if (re == implicitSettingsRootEntry) continue;
      if (!revisionsMap.containsKey(re.getVcsRoot().getId())) {
        missing.add(re.getVcsRoot());
      }
    }

    if (!missing.isEmpty()) throw new RevisionsNotFoundException(Collections.singletonMap(buildType, missing));

    return new ArrayList<>(revisionsMap.values());
  }

  @NotNull
  private static BuildRevisionEx createBuildRevision(@NotNull Revision r, @NotNull VcsRootInstanceEntry rootEntry, @Nullable VcsRootInstanceEntry implicitSettingsRootEntry) {
    long modId = findVcsModification(r.displayRevision, rootEntry.getVcsRoot());
    BuildRevisionEx rev = new BuildRevisionEx(rootEntry, new RepositoryVersion(r.displayRevision, r.displayRevision, r.vcsBranchName), modId);
    if (rootEntry == implicitSettingsRootEntry) {
      rev.setCheckoutMode(BuildRevisionCheckoutMode.MANUAL);
      rev.setType(BuildRevisionVcsRootType.PROJECT_SETTINGS);
    }
    return rev;
  }

  private static long findVcsModification(@NotNull String revision, @NotNull VcsRootInstance rootInstance) {
    long modId = -1L;
    SVcsModification mod = ((VcsRootInstanceEx)rootInstance).findModificationByVersion(revision);
    if (mod != null) {
      modId = mod.getId();
    }
    return modId;
  }

  @Nullable
  private static VcsRootInstanceEntry findVcsRootEntry(@NotNull Map<Long, VcsRootInstanceEntry> vcsRootsMap, @NotNull Map<String, VcsRootInstanceEntry> vcsRootsExtIdsMap, @NotNull Revision revision) {
    VcsRootInstanceEntry rootEntry = null;
    if (revision.vcsRoot.id != null) {
      long rootId;
      try {
        rootId = Long.parseLong(revision.vcsRoot.id);
      } catch (NumberFormatException e) {
        return null;
      }

      rootEntry = vcsRootsMap.get(rootId);
    } else if (revision.vcsRoot.vcsRootId != null) { // external id of a parent VCS root
      rootEntry = vcsRootsExtIdsMap.get(revision.vcsRoot.vcsRootId);
    }

    return rootEntry;
  }

  private static boolean sameCheckoutRules(@NotNull CheckoutRules r1, @NotNull CheckoutRules r2) {
    return r1.getIncludeRules().equals(r2.getIncludeRules()) && r1.getExcludeRules().equals(r2.getExcludeRules());
  }

  @NotNull
  private String getRelatedBuildDescription() {
    Long promotionId = getPromotionIdOfSubmittedBuild();
    return promotionId == null ? "" : " in the submitted build with id '" + promotionId + "'";
  }

  private void setDepsWithNullCheck(@NotNull final BuildCustomizer customizer, @Nullable final List<SArtifactDependency> newDeps) {
    if (newDeps == null || newDeps.isEmpty()) {
      throw new BadRequestException("Attempt to set empty artifact dependencies collection which is not supported by TeamCity API"); //TeamCity API
    }
    customizer.setArtifactDependencies(newDeps);
  }

  @NotNull
  private List<SArtifactDependency> getBuildPatchedDeps(@NotNull final List<SArtifactDependency> originalDeps,
                                                        final boolean useAllOriginalDeps,
                                                        final @NotNull ServiceLocator serviceLocator, @NotNull final List<BuildPromotion> artifactDepsBuilds) {
    List<SArtifactDependency> originalDepsCopy = useAllOriginalDeps ? new ArrayList<>(originalDeps) : null;
    Map<String, Integer> processedBuildsByBuildTypeExternalId = new HashMap<>();
    final ArtifactDependencyFactory artifactDependencyFactory = serviceLocator.getSingletonService(ArtifactDependencyFactory.class);
    List<SArtifactDependency> result = new ArrayList<>(artifactDepsBuilds.size());
    for (BuildPromotion source : artifactDepsBuilds) {
      Integer buildTypeProcessedBuilds = processedBuildsByBuildTypeExternalId.get(source.getBuildTypeExternalId());
      if (buildTypeProcessedBuilds == null) {
        buildTypeProcessedBuilds = 0;
      }
      final SArtifactDependency originalDep = getArtifactDependency(originalDeps, source.getBuildType(), buildTypeProcessedBuilds);
      processedBuildsByBuildTypeExternalId.put(source.getBuildTypeExternalId(), buildTypeProcessedBuilds + 1);
      final SBuild associatedBuild = source.getAssociatedBuild();
      final RevisionRule revisionRule = RevisionRules.newBuildIdRule(source.getId(), associatedBuild != null ? associatedBuild.getBuildNumber() : null);
      final SArtifactDependency dep = artifactDependencyFactory.createArtifactDependency(source.getBuildTypeExternalId(), originalDep.getSourcePaths(), revisionRule);
      dep.setCleanDestinationFolder(originalDep.isCleanDestinationFolder());
      result.add(dep);
      if (useAllOriginalDeps) {
        originalDepsCopy.remove(originalDep);
      }
    }
    if (useAllOriginalDeps && !originalDepsCopy.isEmpty()) {
      result.addAll(originalDepsCopy);
    }
    return result;
  }

  @NotNull
  private SArtifactDependency getArtifactDependency(@NotNull final List<SArtifactDependency> originalDeps, @NotNull final SBuildType sourceBuildType, final int index) {
    int processedBuildsOfSourceBuildType = 0;
    for (SArtifactDependency dependency : originalDeps) {
      String dependencyBuildTypeExternalId = dependency.getSourceExternalId();
      if (sourceBuildType.getExternalId().equals(dependencyBuildTypeExternalId)) {
        if (index == processedBuildsOfSourceBuildType) {
          return dependency;
        }
        processedBuildsOfSourceBuildType++;
      }
    }
    if (processedBuildsOfSourceBuildType == 0) {
      throw new BadRequestException("No artifact dependency on build type with id " + sourceBuildType.getExternalId() + " is found" + getRelatedBuildDescription() +
                                    ". Make sure it is present in the build type settings or submit it in 'custom-artifact-dependencies' node.");
    }
    throw new BadRequestException("Only " + processedBuildsOfSourceBuildType + " artifact dependencies on build type with id " + sourceBuildType.getExternalId() +
                                  " are found" + getRelatedBuildDescription() + ", but at least " + index + " builds of the build type are passed as artifact dependencies.");
  }

  /**
   * @return null if the submitted build type does not contain any custom settings
   */
  @Nullable
  private BuildTypeEx getCustomizedSubmittedBuildType(@NotNull ServiceLocator serviceLocator) {
    if (submittedBuildType == null) {
      return null;
    }

    final BuildTypeOrTemplate customizedBuildTypeFromPosted =
      submittedBuildType.getCustomizedBuildTypeFromPosted(serviceLocator.findSingletonService(BuildTypeFinder.class), serviceLocator);
    if (customizedBuildTypeFromPosted == null) {
      return null;
    }

    return (BuildTypeEx)customizedBuildTypeFromPosted.getBuildType();
  }

  private SBuildType getSubmittedBuildType(@NotNull ServiceLocator serviceLocator, @Nullable final SVcsModification personalChange, @Nullable final SUser currentUser) {
    if (submittedBuildType == null) {
      if (submittedBuildTypeId == null) {
        throw new BadRequestException("No 'buildType' element in the posted entry.");
      }
      SBuildType buildType = serviceLocator.getSingletonService(ProjectManager.class).findBuildTypeByExternalId(submittedBuildTypeId);
      if (buildType == null) {
        throw new NotFoundException("No build type found by submitted id '" + submittedBuildTypeId + "'");
      }
      return buildType;
    }

    final BuildTypeOrTemplate buildTypeFromPosted = submittedBuildType.getBuildTypeFromPosted(serviceLocator.findSingletonService(BuildTypeFinder.class));
    final SBuildType regularBuildType = buildTypeFromPosted.getBuildType();
    if (regularBuildType == null) {
      throw new BadRequestException("Found template instead on build type. Only build types can run builds.");
    }

    if (personalChange == null) {
      return regularBuildType;
    }
    if (currentUser == null) {
      throw new BadRequestException("Cannot trigger a personal build while no current user is present. Please specify credentials of a valid and non-special user.");
    }
    return ((BuildTypeEx)regularBuildType).createPersonalBuildType(currentUser, personalChange.getId());
  }

  @NotNull
  public SQueuedBuild triggerBuild(@Nullable final SUser user, @NotNull final ServiceLocator serviceLocator, @NotNull final Map<Long, Long> buildPromotionIdReplacements) {
    BuildPromotion buildToTrigger = getBuildToTrigger(user, serviceLocator, buildPromotionIdReplacements);
    SQueuedBuild queuedBuild = triggerBuild((BuildPromotionEx)buildToTrigger, user,
                                            submittedAgent == null ? null : submittedAgent.getAgentRestrictor(serviceLocator), serviceLocator); //TeamCity API issue: cast
    if (queuedBuild == null) {
      throw new InvalidStateException("Failed to add build for build type with id '" + buildToTrigger.getBuildTypeExternalId() + "' into the queue for unknown reason.");
    }
    if (submittedTriggeringOptions != null && submittedTriggeringOptions.queueAtTop != null && submittedTriggeringOptions.queueAtTop) {
      serviceLocator.getSingletonService(BuildQueue.class).moveTop(queuedBuild.getItemId());
    }
    return queuedBuild;
  }

  @Nullable
  private SQueuedBuild triggerBuild(@NotNull final BuildPromotionEx buildToTrigger, @Nullable final SUser user, @Nullable final SAgentRestrictor agentRestrictor,
                                    @NotNull final ServiceLocator serviceLocator) {
    final BuildTypeEx bt = buildToTrigger.getBuildType();
    if (bt == null) return null;
    final String triggeredBy = getTriggeredBy(user, serviceLocator);

    if (agentRestrictor != null) {
      return bt.addToQueue(agentRestrictor, buildToTrigger, triggeredBy);
    }

    return bt.addToQueue(buildToTrigger, triggeredBy);
  }

  @NotNull
  private String getTriggeredBy(@Nullable final SUser user, @NotNull final ServiceLocator serviceLocator) {
    if (TeamCityProperties.getBoolean("rest.beans.build.triggeredBy.allowRawValueSubmit")) {
      if (submittedTriggeredBy != null && submittedTriggeredBy.rawValue != null) return submittedTriggeredBy.rawValue;
    }
    String defaultType = user != null ? "user" : "request";
    TriggeredByBuilder result;
    if (submittedTriggeredBy != null) {
      result = submittedTriggeredBy.getFromPosted(defaultType, serviceLocator);
    } else {
      result = new TriggeredByBuilder();
      result.addParameter(TriggeredByBuilder.TYPE_PARAM_NAME, defaultType);
    }
    if (user != null) {
      result.addParameter(TriggeredByBuilder.USER_PARAM_NAME, String.valueOf(user.getId()));
    }
    result.addParameter("origin", "rest");
    return result.toString();
  }

  @Nullable
  public static String getFieldValue(@NotNull final BuildPromotion buildPromotion, @Nullable final String field, @NotNull final BeanContext beanContext) {
    final Build build = new Build(buildPromotion, Fields.ALL, beanContext);

    if ("number".equals(field)) { //supporting number here in addition to BuildRequest as this method is used from other requests classes as well
      return build.getNumber();
    } else if ("status".equals(field)) {
      return build.getStatus();
    } else if ("statusText".equals(field)) {
      return build.getStatusText();
    } else if ("id".equals(field)) {
      return String.valueOf(build.getId());
    } else if ("state".equals(field)) {
      return build.getState().toString();
    } else if ("failedToStart".equals(field)) {
      return String.valueOf(build.isFailedToStart());
    } else if ("startEstimateDate".equals(field)) {
      return build.getStartEstimate();
    } else if (FINISH_ESTIMATE.equals(field)) {
      return build.getFinishEstimate();
    } else if ("percentageComplete".equals(field)) {
      return String.valueOf(build.getPercentageComplete());
    } else if ("personal".equals(field)) {
      return String.valueOf(build.isPersonal());
    } else if ("usedByOtherBuilds".equals(field)) {
      return String.valueOf(build.isUsedByOtherBuilds());
    } else if ("queuedDate".equals(field)) {
      return build.getQueuedDate();
    } else if ("startDate".equals(field)) {
      return build.getStartDate();
    } else if ("finishDate".equals(field)) {
      return build.getFinishDate();
    } else if ("buildTypeId".equals(field)) {
      return build.getBuildTypeId();
    } else if ("buildTypeInternalId".equals(field)) {
      return buildPromotion.getBuildTypeId();
    } else if ("branchName".equals(field)) {
      return build.getBranchName();
    } else if ("branch".equals(field)) {
      Branch branch = buildPromotion.getBranch();
      return branch == null ? "" : branch.getName();
    } else if ("defaultBranch".equals(field)) {
      return String.valueOf(build.getDefaultBranch());
    } else if ("unspecifiedBranch".equals(field)) {
      return String.valueOf(build.getUnspecifiedBranch());
    } else if (PROMOTION_ID.equals(field) || "promotionId".equals(field)) { //Experimental support only
      return (String.valueOf(build.getPromotionId()));
    } else if ("modificationId".equals(field)) { //Experimental support only
      return String.valueOf(buildPromotion.getLastModificationId());
    } else if ("chainModificationId".equals(field)) { //Experimental support only
      return String.valueOf(((BuildPromotionEx)buildPromotion).getChainModificationId());
    } else if ("commentText".equals(field)) { //Experimental support only
      final Comment comment = build.getComment();
      return comment == null ? null : comment.text;
    } else if ("collectChangesError".equals(field)) { //Experimental support only
      return ((BuildPromotionEx)buildPromotion).getCollectChangesError();
    } else if ("changesCollectingInProgress".equals(field)) { //Experimental support only
      return String.valueOf(((BuildPromotionEx)buildPromotion).isChangesCollectingInProgress());
    } else if ("changeCollectingNeeded".equals(field)) { //Experimental support only
      return String.valueOf(((BuildPromotionEx)buildPromotion).isChangeCollectingNeeded());
    } else if ("revision".equals(field)) { //Experimental support only
      final List<BuildRevision> revisions = buildPromotion.getRevisions();
      return revisions.size() != 1 ? String.valueOf(revisions.get(0).getRevision()) : null;
    } else if ("settingsHash".equals(field)) { //Experimental support only to get settings digest
      return new String(Hex.encodeHex(((BuildPromotionEx)buildPromotion).getSettingsDigest(false)));
    } else if ("currentSettingsHash".equals(field)) { //Experimental support only to get settings digest
      return new String(Hex.encodeHex(((BuildPromotionEx)buildPromotion).getBuildSettings().getDigest()));
    }

    final SBuild associatedBuild = buildPromotion.getAssociatedBuild();
    final SQueuedBuild queuedBuild = buildPromotion.getQueuedBuild();

    if ("triggeredBy.username".equals(field)) { //Experimental support only
      if (associatedBuild != null) {
        final SUser user = associatedBuild.getTriggeredBy().getUser();
        return user == null ? null : user.getUsername();
      }
      if (queuedBuild != null) {
        final SUser user = queuedBuild.getTriggeredBy().getUser();
        return user == null ? null : user.getUsername();
      }
      return null;
    } else if ("triggeredBy.raw".equals(field)) { //Experimental support only
      if (associatedBuild != null) {
        return associatedBuild.getTriggeredBy().getRawTriggeredBy();
      }
      if (queuedBuild != null) {
        return queuedBuild.getTriggeredBy().getRawTriggeredBy();
      }
      return null;
    }

    throw new NotFoundException("Field '" + field + "' is not supported. Supported are: number, status, statusText, id, startDate, finishDate, buildTypeId, branchName.");
  }

  @Nullable
  public static BuildChanges getArtifactDependencyChangesNode(@NotNull final BuildPromotion build, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
    final Long buildId = build.getAssociatedBuildId();
    if (buildId != null && buildId <= 0) {  //see BuildPromotionImpl.getDetectedChangesProviders
      return null;
    }
    return new BuildChanges(Build.getArtifactDependencyChanges(build, beanContext.getServiceLocator()), fields, beanContext);
  }

  @NotNull
  private static List<BuildChangeData> getArtifactDependencyChanges(@NotNull final BuildPromotion build, @NotNull final ServiceLocator serviceLocator) {
    //see also jetbrains.buildServer.server.rest.data.finder.impl.ChangeFinder.getBuildChanges

    ArtifactDependencyChangesProvider changesProvider = new ArtifactDependencyChangesProvider(build, ChangeFinder.getBuildChangesPolicy(),
                                                                                              serviceLocator.getSingletonService(BuildsManager.class),
                                                                                              serviceLocator.getSingletonService(DownloadedArtifactsLoggerImpl.class));

    List<ChangeDescriptor> detectedChanges = changesProvider.getDetectedChanges();
    if (detectedChanges.isEmpty()) {
      return Collections.emptyList();
    }
    if (detectedChanges.size() > 1) {
      throw new OperationException("Unexpected state: more than one (" + detectedChanges.size() + ") artifact changes found");
    }
    ChangeDescriptor changeDescriptor = detectedChanges.get(0);
    if (!ChangeDescriptorConstants.ARTIFACT_DEPENDENCY_CHANGE.equals(changeDescriptor.getType())) {
      throw new OperationException("Unexpected state: unknown type of artifact dependency change: '" + changeDescriptor.getType() + "'");
    }
    try {
      Object o = changeDescriptor.getAssociatedData().get(ArtifactDependencyChangesProvider.CHANGED_DEPENDENCIES_ATTR);
      //Actually result is List<ArtifactDependencyChangesProvider.ArtifactsChangeDescriptor>
      if (o == null) {
        return Collections.emptyList();
      } else {
        //noinspection unchecked
        return ((List<ChangeDescriptor>)o).stream()
                                          .map(descr -> {
                                            Map<String, Object> associatedData = descr.getAssociatedData();
                                            SBuild prevBuild = (SBuild)associatedData.get(ArtifactDependencyChangesProvider.OLD_BUILD_ATTR);
                                            SBuild nextBuild = (SBuild)associatedData.get(ArtifactDependencyChangesProvider.NEW_BUILD_ATTR);
                                            if (prevBuild == null && nextBuild == null) return null;
                                            return new BuildChangeData(Util.resolveNull(prevBuild, (b) -> b.getBuildPromotion()),
                                                                       Util.resolveNull(nextBuild, (b) -> b.getBuildPromotion()));
                                          })
                                          .filter(Objects::nonNull).collect(Collectors.toList());
      }
    } catch (Exception e) {
      throw new OperationException("Unexpected state while getting artifact dependency details: " + e.toString(), e);
    }
  }

}
