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

package jetbrains.buildServer.server.rest.model.buildType;

import com.intellij.openapi.diagnostic.Logger;
import io.swagger.annotations.ApiModelProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.clouds.CloudImage;
import jetbrains.buildServer.server.rest.APIController;
import jetbrains.buildServer.server.rest.data.BranchData;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.PagedSearchResult;
import jetbrains.buildServer.server.rest.data.PermissionChecker;
import jetbrains.buildServer.server.rest.data.finder.TypedFinderBuilder;
import jetbrains.buildServer.server.rest.data.finder.impl.*;
import jetbrains.buildServer.server.rest.data.investigations.InvestigationFinder;
import jetbrains.buildServer.server.rest.data.investigations.InvestigationWrapper;
import jetbrains.buildServer.server.rest.data.parameters.EntityWithParameters;
import jetbrains.buildServer.server.rest.data.parameters.InheritableUserParametersHolderEntityWithParameters;
import jetbrains.buildServer.server.rest.data.parameters.ParametersPersistableEntity;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.*;
import jetbrains.buildServer.server.rest.model.agent.Agents;
import jetbrains.buildServer.server.rest.model.build.Branches;
import jetbrains.buildServer.server.rest.model.build.Builds;
import jetbrains.buildServer.server.rest.model.cloud.CloudImages;
import jetbrains.buildServer.server.rest.model.project.Project;
import jetbrains.buildServer.server.rest.request.AgentRequest;
import jetbrains.buildServer.server.rest.request.BuildTypeRequest;
import jetbrains.buildServer.server.rest.request.InvestigationRequest;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.BuildTypeOrTemplate;
import jetbrains.buildServer.server.rest.util.CachingValue;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.auth.AuthUtil;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.auth.SecurityContext;
import jetbrains.buildServer.serverSide.identifiers.BuildTypeIdentifiersManager;
import jetbrains.buildServer.serverSide.impl.BuildTypeImpl;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.impl.Lazy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: Yegor Yarko
 * Date: 29.03.2009
 */
@XmlRootElement(name = "buildType")
@XmlType(name = "buildType", propOrder = {"id", "internalId", "name", "templateFlag", "type", "paused", "uuid", "description", "projectName", "projectId", "projectInternalId",
  "href", "webUrl", "inherited" /*used only for list of build configuration templates*/,
  "links", "project", "templates", "template" /*deprecated*/, "vcsRootEntries", "settings", "parameters", "steps", "features", "triggers", "snapshotDependencies",
  "artifactDependencies", "agentRequirements",
  "branches", "builds", "investigations", "compatibleAgents", "compatibleCloudImages",
  "vcsRootInstances", "externalStatusAllowed", "pauseComment" /*experimental*/})
@ModelDescription(
    value = "Represents a build configuration.",
    externalArticleLink = "https://www.jetbrains.com/help/teamcity/creating-and-editing-build-configurations.html",
    externalArticleName = "Build Configuration"
)
public class BuildType {
  private static final Logger LOG = Logger.getInstance(BuildType.class.getName());

  @Nullable
  protected BuildTypeOrTemplate myBuildType;

  @NotNull
  private String myExternalId;

  @Nullable
  private String myInternalId;

  @Nullable
  private final Boolean myInherited;

  private final boolean canViewSettings;

  private Fields myFields = Fields.LONG;

  @NotNull
  private BeanContext myBeanContext;

  public BuildType() {
    canViewSettings = true;
    myInherited = null;
  }

  public BuildType(@NotNull final BuildTypeOrTemplate buildType, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
    myInherited =  buildType.isInherited();
    if ((buildType instanceof BuildTypeOrTemplate.IdsOnly)) {
      canViewSettings = initForIds(buildType.getId(), buildType.getInternalId(), fields, beanContext);
      return;
    }

    myBuildType = buildType;
    myExternalId = buildType.getId();
    myInternalId = buildType.getInternalId();
    myFields = fields;
    myBeanContext = beanContext;
    final PermissionChecker permissionChecker = beanContext.getServiceLocator().findSingletonService(PermissionChecker.class);
    assert permissionChecker != null;
    canViewSettings = !shouldRestrictSettingsViewing(buildType.get(), permissionChecker);
  }

  public BuildType(@NotNull final String externalId, @Nullable final String internalId, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
    canViewSettings = initForIds(externalId, internalId, fields, beanContext);
    myInherited =  null;
  }

  private boolean initForIds(final @NotNull String externalId, final @Nullable String internalId, final @NotNull Fields fields, final @NotNull BeanContext beanContext) {
    final boolean canViewSettings;
    myBuildType = null;
    myExternalId = externalId;
    myInternalId = internalId;
    myFields = fields;
    myBeanContext = beanContext;
    //noinspection RedundantIfStatement
    if (TeamCityProperties.getBooleanOrTrue("rest.beans.buildType.checkPermissions")) {
      canViewSettings = false;
    } else {
      canViewSettings = true;
    }
    return canViewSettings;
  }

  /**
   * @return External id of the build configuration
   */
  @XmlAttribute
  public String getId() {
    return myBuildType == null ? myExternalId : ValueWithDefault.decideDefault(myFields.isIncluded("id", true), () -> myBuildType.getId());
  }

  @XmlAttribute
  public String getInternalId() {
    final boolean includeProperty = TeamCityProperties.getBoolean(APIController.INCLUDE_INTERNAL_ID_PROPERTY_NAME);
    return myBuildType == null ? myInternalId : ValueWithDefault.decideDefault(myFields.isIncluded("internalId", includeProperty, includeProperty), () -> myBuildType.getInternalId());
  }

  @XmlAttribute
  public String getName() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("name"), () -> myBuildType.getName());
  }

  @XmlAttribute
  public String getProjectId() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("projectId"), () -> myBuildType.getProject().getExternalId());
  }

  @XmlAttribute
  @SuppressWarnings("unused")
  public String getProjectInternalId() {
    final boolean includeProperty = TeamCityProperties.getBoolean(APIController.INCLUDE_INTERNAL_ID_PROPERTY_NAME);
    return myBuildType == null
           ? null
           : ValueWithDefault.decideDefault(myFields.isIncluded("projectInternalId", includeProperty, includeProperty), () -> myBuildType.getProject().getProjectId());
  }

  /**
   * @return
   * @deprecated since 01.2014
   */
  @XmlAttribute
  @Deprecated
  public String getProjectName() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("projectName"), () -> myBuildType.getProject().getFullName());
  }

  @XmlAttribute
  public String getHref() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("href"), () -> myBeanContext.getApiUrlBuilder().getHref(myBuildType));
  }

  @XmlAttribute
  public String getDescription() {
    if (myBuildType == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("description"), () -> {
      String description = myBuildType.getDescription();
      return StringUtil.isEmpty(description) ? null : description;
    });
  }

  @XmlAttribute (name = "templateFlag")
  public Boolean getTemplateFlag() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("templateFlag"), () -> !myBuildType.isBuildType());
  }

  /**
   * Experimental use only.
   * The original value is stored in the "settings" in a property named "buildConfigurationType". This one is provided only for convenience.
   * Unlike "settings", this one does not identify if the value is coming from a template.
   */
  @XmlAttribute (name = "type")
  @ApiModelProperty(allowableValues = "regular, composite, deployment")
  public String getType() {
    if(myBuildType == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("type",false, false),
      () -> Util.resolveNull(myBuildType.getSettingsEx(), (e) -> e.getOption(BuildTypeOptions.BT_BUILD_CONFIGURATION_TYPE).toLowerCase()), s -> BuildTypeOptions.BuildConfigurationType.REGULAR.name().equalsIgnoreCase(s)
    );
  }

  @XmlAttribute
  @SuppressWarnings("unused")
  public Boolean isExternalStatusAllowed() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("externalStatusAllowed", false, false), () -> myBuildType.getSettingsEx().getOption(BuildTypeOptions.BT_ALLOW_EXTERNAL_STATUS));
  }

  @XmlAttribute
  public Boolean isPaused() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("paused"), () -> myBuildType.isPaused());
  }

  @XmlElement
  @SuppressWarnings("unused")
  public Comment getPauseComment() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("pauseComment", false, false), () -> {
      jetbrains.buildServer.serverSide.comments.Comment pauseComment;
      if (myBuildType != null && myBuildType.getBuildType() != null && (pauseComment = myBuildType.getBuildType().getPauseComment()) != null) {
        return new Comment(pauseComment, myFields.getNestedField("pauseComment"), myBeanContext);
      } else {
        return null;
      }
    });
  }

  @XmlAttribute
  public Boolean isInherited() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("inherited"), myInherited);
  }

  @XmlAttribute
  public String getUuid() {
    if (myBuildType != null && myFields.isIncluded("uuid", false, false)) {
      //do not expose uuid to usual users as uuid can be considered secure information, e.g. see https://youtrack.jetbrains.com/issue/TW-38605
      if (canEdit()) {
        return ((BuildTypeIdentityEx)myBuildType.getIdentity()).getEntityId().getConfigId();
      } else {
        return null;
      }
    } else {
      return null;
    }
  }

  /**
   * "Canonical" URL for the build configuration's web UI page: it is absolute and uses configured Server URL as a base
   */
  @XmlAttribute
  public String getWebUrl() {
    //template has no user link
    if (myBuildType == null || myBuildType.getBuildType() == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("webUrl"), () -> myBeanContext.getSingletonService(WebLinks.class).getConfigurationHomePageUrl(myBuildType.getBuildType()));
  }

  @XmlElement
  public Links getLinks() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("links", false, false), () -> {
        WebLinks webLinks = myBeanContext.getSingletonService(WebLinks.class);
        RelativeWebLinks relativeWebLinks = new RelativeWebLinks();
        Links.LinksBuilder builder = new Links.LinksBuilder();
        if (myBuildType.getBuildType() != null) {
          builder.add(
            Link.WEB_VIEW_TYPE, webLinks.getConfigurationHomePageUrl(myBuildType.getBuildType()), relativeWebLinks.getConfigurationHomePageUrl(myBuildType.getBuildType()));
        }
        if (canEdit()) {
          if (myBuildType.isBuildType()) {
            builder.add(Link.WEB_EDIT_TYPE, webLinks.getEditConfigurationPageUrl(myExternalId), relativeWebLinks.getEditConfigurationPageUrl(myExternalId));
          } else if (myBuildType.isTemplate()) {
            builder.add(Link.WEB_EDIT_TYPE, webLinks.getEditTemplatePageUrl(myExternalId), relativeWebLinks.getEditTemplatePageUrl(myExternalId));
          }
        } else {
          PermissionChecker permissionChecker = myBeanContext.getSingletonService(PermissionChecker.class);
          if (AuthUtil.adminSpaceAvailable(permissionChecker.getCurrent()) &&
              permissionChecker.isPermissionGranted(Permission.VIEW_BUILD_CONFIGURATION_SETTINGS, myBuildType.getProject().getProjectId())) {
            if (myBuildType.isBuildType()) {
              builder.add(Link.WEB_VIEW_SETTINGS_TYPE, webLinks.getEditConfigurationPageUrl(myExternalId), relativeWebLinks.getEditConfigurationPageUrl(myExternalId));
            } else if (myBuildType.isTemplate()) {
              builder.add(Link.WEB_VIEW_SETTINGS_TYPE, webLinks.getEditTemplatePageUrl(myExternalId), relativeWebLinks.getEditTemplatePageUrl(myExternalId));
            }
          }
        }
        return builder.build(myFields.getNestedField("links"));
    });
  }

  private boolean canEdit() {
    assert myBuildType != null;
    return myBeanContext.getSingletonService(PermissionChecker.class).isPermissionGranted(Permission.EDIT_PROJECT, myBuildType.getProject().getProjectId());
  }

  @XmlElement(name = "project")
  public Project getProject() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("project", false),
        () -> myBuildType == null ? null : new Project(myBuildType.getProject(), myFields.getNestedField("project"), myBeanContext)
    );
  }

  @XmlElement(name = "templates")
  public BuildTypes getTemplates() {
    if (myBuildType == null || myBuildType.getBuildType() == null){
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("templates", false), check(() -> {
      Fields nestedFields = myFields.getNestedField("templates", Fields.NONE, Fields.LONG);
      return getTemplates(myBuildType.getBuildType(), nestedFields, myBeanContext);
    }));
  }

  @Nullable
  public static BuildTypes getTemplates(@NotNull final SBuildType buildType, @NotNull final Fields fields, final BeanContext beanContext) {
    try {
      PermissionChecker permissionChecker = beanContext.getSingletonService(PermissionChecker.class);
      List<? extends BuildTypeTemplate> templates = buildType.getTemplates();
      Set<String> ownTemplatesIds = buildType.getOwnTemplates().stream().map(t -> t.getInternalId()).collect(Collectors.toSet());
      return new BuildTypes(templates.stream().map(
        t -> shouldRestrictSettingsViewing(t, permissionChecker) ? new BuildTypeOrTemplate.IdsOnly(t.getExternalId(), t.getInternalId()) : new BuildTypeOrTemplate(t))
                                     .map(t -> t.markInherited(!ownTemplatesIds.contains(t.getInternalId()))).collect(Collectors.toList()), null, fields, beanContext);
    } catch (RuntimeException e) {
      LOG.debug("Error retrieving templates for build configuration " + LogUtil.describe(buildType) + ": " + e.toString(), e);
      List<String> templateIds = ((BuildTypeImpl)buildType).getOwnTemplateIds();
      if (templateIds.isEmpty()) return null;
      List<BuildTypeOrTemplate> result = getBuildTypeOrTemplates(templateIds, fields.getNestedField("template"), beanContext);
      return result.isEmpty() ? null : new BuildTypes(result, null, fields, beanContext);
    }
  }

  @NotNull
  private static List<BuildTypeOrTemplate> getBuildTypeOrTemplates(@NotNull final List<String> templateInternalIds,
                                                                   @NotNull final Fields fields,
                                                                   @NotNull final BeanContext beanContext) {
    //still including external id since the user has permission to view settings of the current build configuration
    ProjectManager projectManager = beanContext.getSingletonService(ProjectManager.class);
    try {
      return beanContext.getSingletonService(SecurityContextEx.class).runAsSystem(() ->
        templateInternalIds.stream().map(id -> {
          BuildTypeTemplate template = projectManager.findBuildTypeTemplateById(id);
          if (template == null) return null;
          return new BuildTypeOrTemplate.IdsOnly(template.getExternalId(), id);
        }).collect(Collectors.toList()));
    } catch (Throwable e) {
      LOG.debug("Error retrieving templates external ids for internal ids: " + String.join(", ", templateInternalIds) + " under System: " + e, e);
      return Collections.emptyList();
    }
  }


  /**
   * This is preserved for compatibility reasons with TeamCity before 2017.2 where only one template can be used in a build configuration
   * @return the first template used in the build configuration
   * @Deprecated use getTemplates
   */
  @XmlElement(name = "template")
  public BuildType getTemplate() {
    if (myBuildType == null || myBuildType.getBuildType() == null){
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("template", false, false), check(() -> {
        try {
          final BuildTypeTemplate template = myBuildType.getBuildType().getTemplate();
          return template == null ? null : new BuildType(new BuildTypeOrTemplate(template), myFields.getNestedField("template"), myBeanContext);
        } catch (RuntimeException e) {
          LOG.debug("Error retrieving template for build configuration " + LogUtil.describe(myBuildType.getBuildType()) + ": " + e, e);
          String templateId = myBuildType.getBuildType().getTemplateId();
          //still including external id since the user has permission to view settings of the current build configuration
          String templateExternalId = getTemplateExternalId(myBuildType.getBuildType());
          return templateId == null || templateExternalId == null ? null : new BuildType(templateExternalId, templateId, myFields.getNestedField("template"), myBeanContext);
        }
    }));
  }

  @Nullable
  private String getTemplateExternalId(@NotNull final SBuildType buildType) {
    try {
      return myBeanContext.getSingletonService(SecurityContextEx.class).runAsSystem(() -> {
        BuildTypeTemplate template = buildType.getTemplate();
        return template == null ? null : template.getExternalId();
      });
    } catch (Throwable e) {
      LOG.debug("Error retrieving template external id for build configuration " + LogUtil.describe(buildType) + " under System: " + e, e);
      return null;
    }
  }

  @XmlElement(name = "vcs-root-entries")
  public VcsRootEntries getVcsRootEntries() {
    return ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("vcs-root-entries", false),
        check(() -> myBuildType == null ? null : new VcsRootEntries(myBuildType, myFields.getNestedField("vcs-root-entries"), myBeanContext))
    );
  }

  /**
   * Experimental use only.
   */
  @XmlElement(name = "vcsRootInstances")
  public VcsRootInstances getVcsRootInstances() {
    return ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("vcsRootInstances", false, false)
      , check(() -> myBuildType == null || myBuildType.getBuildType() == null ? null :
                    new VcsRootInstances(CachingValue.simple(myBuildType.getBuildType().getVcsRootInstances()), null, myFields.getNestedField("vcsRootInstances"), myBeanContext)));
  }

  @XmlElement(name = "branches")
  public Branches getBranches() {
    if (myBuildType == null || myBuildType.getBuildType() == null) return null;
    return ValueWithDefault.decideDefault(myFields.isIncluded("branches", false, false), // do not include until asked as should only include for branched build types
      () -> {
        String href;
        final Fields nestedFields = myFields.getNestedField("branches");
        final String locator = nestedFields.getLocator();
        if (locator != null) {
          BranchFinder branchFinder = myBeanContext.getSingletonService(BranchFinder.class);
          List<BranchData> result = branchFinder.getItems(myBuildType.getBuildType(), locator).getEntries();
          href = BuildTypeRequest.getBranchesHref(myBuildType.getBuildType(), locator);
          return new Branches(result, new PagerDataImpl(href), nestedFields, myBeanContext);
        }
        href = BuildTypeRequest.getBranchesHref(myBuildType.getBuildType(), null);
        return new Branches(null, new PagerDataImpl(href), nestedFields, myBeanContext);
      });
  }

  /**
   * Link to builds of this build configuration. Is not present for templates.
   * @return
   */
  @XmlElement(name = "builds")
  public Builds getBuilds() {
    if (myBuildType == null || !myBuildType.isBuildType()) return null;
    if (!myFields.isIncluded("builds", false, true)){
      return null;
    }

    return ValueWithDefault.decideDefault(myFields.isIncluded("builds", false), () -> {
        String buildsHref;
        List<BuildPromotion> builds = null;
        final Fields buildsFields = myFields.getNestedField("builds");
        final String buildsLocator = buildsFields.getLocator();
        if (buildsLocator != null) {
          builds = myBeanContext.getSingletonService(BuildPromotionFinder.class).getBuildPromotionsWithLegacyFallback(myBuildType.getBuildType(), buildsLocator).getEntries();
          buildsHref = BuildTypeRequest.getBuildsHref(myBuildType.getBuildType(), buildsLocator);
        } else {
          buildsHref = BuildTypeRequest.getBuildsHref(myBuildType.getBuildType());
        }
        return Builds.createFromPrefilteredBuildPromotions(builds, new PagerDataImpl(buildsHref), buildsFields, myBeanContext);
    });
  }

  @XmlElement
  public Properties getParameters() {
    return myBuildType == null ? null : ValueWithDefault
        .decideIncludeByDefault(myFields.isIncluded("parameters", false),
            check(() -> new Properties(
                createEntity(myBuildType),
                BuildTypeRequest.getParametersHref(myBuildType),
                null,
                myFields.getNestedField("parameters", Fields.NONE, Fields.LONG),
                myBeanContext
            ))
        );
  }

  @XmlElement(name = "steps")
  public PropEntitiesStep getSteps() {
    return myBuildType == null ? null : ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("steps", false), check(() -> {
        return new PropEntitiesStep(myBuildType.getSettingsEx(), myFields.getNestedField("steps", Fields.NONE, Fields.LONG), myBeanContext);
    }));
  }

  @XmlElement(name = "features")
  public PropEntitiesFeature getFeatures() {
    return myBuildType == null ? null : ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("features", false), check(() -> {
        return new PropEntitiesFeature(myBuildType.getSettingsEx(), myFields.getNestedField("features", Fields.NONE, Fields.LONG), myBeanContext);
    }));
  }

  @XmlElement(name = "triggers")
  public PropEntitiesTrigger getTriggers() {
    return myBuildType == null ? null : ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("triggers", false), check(() -> {
        return new PropEntitiesTrigger(myBuildType.getSettingsEx(), myFields.getNestedField("triggers", Fields.NONE, Fields.LONG), myBeanContext);
    }));
  }


  @XmlElement(name = "snapshot-dependencies")
  public PropEntitiesSnapshotDep getSnapshotDependencies() {
    return myBuildType == null ? null :
           ValueWithDefault.decideIncludeByDefault(
               myFields.isIncluded("snapshot-dependencies", false),
               check(() -> {
                 return new PropEntitiesSnapshotDep(myBuildType.getSettingsEx(), myFields
                     .getNestedField("snapshot-dependencies", Fields.NONE, Fields.LONG), myBeanContext);
               })
           );
  }

  @XmlElement(name = "artifact-dependencies")
  public PropEntitiesArtifactDep getArtifactDependencies() {
    if (myBuildType == null) {
      return null;
    } else {
      return ValueWithDefault.decideIncludeByDefault(myFields.isIncluded("artifact-dependencies", false), check(
        () -> new PropEntitiesArtifactDep(myBuildType.getSettingsEx(), myFields.getNestedField("artifact-dependencies", Fields.NONE, Fields.LONG), myBeanContext)
      ));
    }
  }

  //todo: consider exposing implicit requirements as well
  @XmlElement(name = "agent-requirements")
  public PropEntitiesAgentRequirement getAgentRequirements() {
    if (myBuildType == null) {
      return null;
    }
    return ValueWithDefault.decideIncludeByDefault(
      myFields.isIncluded("agent-requirements", false),
      check(() -> new PropEntitiesAgentRequirement(
        myBuildType.getSettingsEx(),
        myFields.getNestedField("agent-requirements", Fields.NONE, Fields.LONG),
        myBeanContext
      ))
    );
  }

  @XmlElement(name="settings")
  public Properties getSettings() {
    return myBuildType == null ? null : ValueWithDefault.decideDefault(myFields.isIncluded("settings", false), check(() -> {
        Fields nestedField = myFields.getNestedField("settings", Fields.NONE, Fields.LONG);
        Locator locator = nestedField.getLocator() == null ? null : new Locator(nestedField.getLocator());
        EntityWithParameters entity = Properties.createEntity(BuildTypeUtil.getSettingsParameters(myBuildType, locator, null, false),
                                                              BuildTypeUtil.getSettingsParameters(myBuildType, null, true, false));
        Properties result = new Properties(entity, null, locator, nestedField, myBeanContext);
        if (locator != null) locator.checkLocatorFullyProcessed();
        return result;
    }));
  }

  /**
   * Link to investigations for this build type
   *
   * @return
   */
  @XmlElement(name = "investigations")
  public Investigations getInvestigations() {
    if (myBuildType == null || myBuildType.getBuildType() == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("investigations", false, true), () -> {
        final Fields nestedFields = myFields.getNestedField("investigations");
        final InvestigationFinder finder = myBeanContext.getSingletonService(InvestigationFinder.class);
        final String actualLocatorText = Locator.merge(nestedFields.getLocator(), InvestigationFinder.getLocator(myBuildType.getBuildType()));
        final List<InvestigationWrapper> result = Investigations.isDataNecessary(nestedFields) ? finder.getItems(actualLocatorText).getEntries() : null;
        return new Investigations(result, new PagerDataImpl(InvestigationRequest.getHref(actualLocatorText)), nestedFields, myBeanContext);
    });
  }

  @XmlElement(name = "compatibleAgents")
  public Agents getCompatibleAgents() {
    if (myBuildType == null || myBuildType.getBuildType() == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("compatibleAgents", false, true), () -> {
        final Fields nestedFields = myFields.getNestedField("compatibleAgents");
        String actualLocatorText = Locator.merge(nestedFields.getLocator(), AgentFinder.getCompatibleAgentsLocator(myBuildType.getBuildType()));
        return new Agents(actualLocatorText, new PagerDataImpl(AgentRequest.getItemsHref(actualLocatorText)), nestedFields, myBeanContext);
    });
  }

  @XmlElement(name = "compatibleCloudImages")
  @SuppressWarnings("unused")
  public CloudImages getCompatibleCloudImages() {
    if (myBuildType == null || myBuildType.getBuildType() == null) {
      return null;
    }
    return ValueWithDefault.decideDefault(myFields.isIncluded("compatibleCloudImages", false, true), () -> {
      return findCompatibleCloudImages(myFields.getNestedField("compatibleCloudImages"));
    });
  }

  @NotNull
  private CloudImages findCompatibleCloudImages(Fields fields) {
    PagedSearchResult<CloudImage> items = myBeanContext.getSingletonService(CloudImageFinder.class).getItems(CloudImageFinder.getCompatibleBuildTypeLocator(myBuildType));
    return new CloudImages(CachingValue.simple(items.getEntries()), null, fields, myBeanContext);
  }

  /**
   * This is used only when posting a link to the build
   */
  private final Lazy<SubmitedParameters> mySubmitted = new Lazy<SubmitedParameters>() {
    @Override
    protected SubmitedParameters createValue() {
      return new SubmitedParameters();
    }
  };

  public void setId(String id) {
    mySubmitted.get().id = id;
  }

  public void setInternalId(String id) {
    mySubmitted.get().internalId = id;
  }

  @XmlAttribute
  public String getLocator() {
    return null;
  }

  public void setLocator(final String locator) {
    mySubmitted.get().locator = locator;
  }

  public void setInherited(final Boolean inherited) {
    mySubmitted.get().inherited = inherited;
  }

  @Nullable
  public String getExternalIdFromPosted(@NotNull final ServiceLocator serviceLocator) {
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.id != null) {
      if (submittedParams.internalId == null) {
        return submittedParams.id;
      }
      String externalByInternal = serviceLocator.getSingletonService(BuildTypeIdentifiersManager.class).internalToExternal(submittedParams.internalId);
      if (externalByInternal == null || submittedParams.id.equals(externalByInternal)) {
        return submittedParams.id;
      }
      throw new BadRequestException(
        "Both external id '" + submittedParams.id + "' and internal id '" + submittedParams.internalId + "' attributes are present and they reference different build types.");
    }
    if (submittedParams.internalId != null) {
      return serviceLocator.getSingletonService(BuildTypeIdentifiersManager.class).internalToExternal(submittedParams.internalId);
    }
    if (submittedParams.locator != null) {
      return serviceLocator.getSingletonService(BuildTypeFinder.class).getBuildType(null, submittedParams.locator, false).getExternalId();
    }
    throw new BadRequestException("Could not find build type by the data. Either 'id' or 'internalId' or 'locator' attributes should be specified.");
  }

  @NotNull
  public String getLocatorFromPosted() {
    String locatorText;
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.locator != null) {
      if (submittedParams.id != null) {
        throw new BadRequestException("Both 'locator' and '" + "id" + "' attributes are specified. Only one should be present.");
      }
      if (submittedParams.internalId != null) {
        throw new BadRequestException("Both 'locator' and '" + "internalId" + "' attributes are specified. Only one should be present.");
      }
      locatorText = submittedParams.locator;
    } else {
      final Locator locator = Locator.createEmptyLocator();
      if (submittedParams.id != null) {
        locator.setDimension("id", mySubmitted.get().id);
      }
      if (submittedParams.internalId != null) {
          locator.setDimension("internalId", submittedParams.internalId);
      }
      if (locator.isEmpty()) {
        throw new BadRequestException("No build specified. Either '" + "id" + "' or 'locator' attributes should be present.");
      }

      locatorText = locator.getStringRepresentation();
    }
    return locatorText;
  }

  /**
   * @return null if nothing is customized
   */
  @Nullable
  public BuildTypeOrTemplate getCustomizedBuildTypeFromPosted(@NotNull final BuildTypeFinder buildTypeFinder, @NotNull final ServiceLocator serviceLocator) {
    final BuildTypeOrTemplate bt = getBuildTypeFromPosted(buildTypeFinder);

    final BuildTypeEx buildType = (BuildTypeEx)bt.getBuildType();
    if (buildType == null) {
      throw new BadRequestException("Cannot change build type template, only build types are supported");
    }

    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.templateFlag != null && submittedParams.templateFlag) {
      throw new BadRequestException("Cannot change build type to template, only build types are supported");
    }

    if (submittedParams.name != null && !submittedParams.name.equals(buildType.getName())) {
      throw new BadRequestException("Cannot change build type name from '" + buildType.getName() + "' to '" + submittedParams.name + "'. Remove the name from submitted build type.");
    }

    // consider checking other unsupported options here, see https://confluence.jetbrains.com/display/TCINT/Versioned+Settings+Freeze
    // At time of 9.1:
    //VCS
    //VCS roots and checkout rules
    //build triggers
    //snapshot dependencies
    //fail build on error message from build runner
    //build features executed on server
    //VCS labeling
    //auto-merge
    //status widget
    //enable/disable of personal builds

    final BuildTypeOrTemplatePatcher buildTypeOrTemplatePatcher = new BuildTypeOrTemplatePatcher() {
      private BuildTypeOrTemplate myCached = null;

      @Override
      @NotNull
      public BuildTypeOrTemplate getBuildTypeOrTemplate() {
        if (myCached == null) myCached = new BuildTypeOrTemplate(buildType.createEditableCopy(false));  //todo: support "true" value for build type "patching"
        return myCached;
      }
    };

    try {
      if (fillBuildTypeOrTemplate(buildTypeOrTemplatePatcher, serviceLocator)) {
        return buildTypeOrTemplatePatcher.getBuildTypeOrTemplate();
      }
    } catch (UnsupportedOperationException e) {
      //this gets thrown when we try to set not supported settings to an editable build type
      throw new BadRequestException("Error changing build type as per submitted settings", e);
    }

    return null;
  }

  @NotNull
  public BuildTypeOrTemplate getBuildTypeFromPosted(@NotNull final BuildTypeFinder buildTypeFinder) {
    String locatorText = "";
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.internalId != null) {
      locatorText = "internalId:" + submittedParams.internalId;
    } else {
      if (submittedParams.id != null) locatorText += (!locatorText.isEmpty() ? "," : "") + "id:" + submittedParams.id;
    }
    if (locatorText.isEmpty()) {
      locatorText = submittedParams.locator;
    } else {
      if (submittedParams.locator != null) {
        throw new BadRequestException("Both 'locator' and 'id' or 'internalId' attributes are specified. Only one should be present.");
      }
    }
    if (StringUtil.isEmpty(locatorText)) {
      throw new BadRequestException("No build type specified. Either 'id', 'internalId' or 'locator' attribute should be present.");
    }
    BuildTypeOrTemplate result = buildTypeFinder.getBuildTypeOrTemplate(null, locatorText, false);
    if (submittedParams.inherited != null) {
      result.markInherited(submittedParams.inherited);
    }
    return result;
  }

  public void setProjectId(@Nullable final String submittedProjectId) {
    mySubmitted.get().projectId = submittedProjectId;
  }

  public void setProject(@Nullable final Project submittedProject) {
    mySubmitted.get().project = submittedProject;
  }

  public void setName(@Nullable final String submittedName) {
    mySubmitted.get().name = submittedName;
  }

  public void setDescription(@Nullable final String submittedDescription) {
    mySubmitted.get().description = submittedDescription;
  }

  public void setTemplateFlag(@Nullable final Boolean submittedTemplateFlag) {
    mySubmitted.get().templateFlag = submittedTemplateFlag;
  }

  public void setType(@Nullable final String submittedType) {
    mySubmitted.get().type = submittedType;
  }

  public void setPaused(@Nullable final Boolean submittedPaused) {
    mySubmitted.get().paused = submittedPaused;
  }

  public void setTemplate(@Nullable final BuildType submittedTemplate) {
    mySubmitted.get().template = submittedTemplate;
  }

  public void setTemplates(@Nullable final BuildTypes submittedTemplates) {
    mySubmitted.get().templates = submittedTemplates;
  }

  public void setVcsRootEntries(@Nullable final VcsRootEntries submittedVcsRootEntries) {
    mySubmitted.get().vcsRootEntries = submittedVcsRootEntries;
  }

  public void setParameters(@Nullable final Properties submittedParameters) {
    mySubmitted.get().parameters = submittedParameters;
  }

  public void setSteps(@Nullable final PropEntitiesStep submittedSteps) {
    mySubmitted.get().steps = submittedSteps;
  }

  public void setFeatures(@Nullable final PropEntitiesFeature submittedFeatures) {
    mySubmitted.get().features = submittedFeatures;
  }

  public void setTriggers(@Nullable final PropEntitiesTrigger submittedTriggers) {
    mySubmitted.get().triggers = submittedTriggers;
  }

  public void setSnapshotDependencies(@Nullable final PropEntitiesSnapshotDep submittedSnapshotDependencies) {
    mySubmitted.get().snapshotDependencies = submittedSnapshotDependencies;
  }

  public void setArtifactDependencies(@Nullable final PropEntitiesArtifactDep submittedArtifactDependencies) {
    mySubmitted.get().artifactDependencies = submittedArtifactDependencies;
  }

  public void setAgentRequirements(@Nullable final PropEntitiesAgentRequirement submittedAgentRequirements) {
    mySubmitted.get().agentRequirements = submittedAgentRequirements;
  }

  public void setSettings(@Nullable final Properties submittedSettings) {
    mySubmitted.get().settings = submittedSettings;
  }

  //used in tests
  public BuildType initializeSubmittedFromUsual() {
    setId(getId());
    setInternalId(getInternalId());
    setLocator(getLocator());
    setInherited(isInherited());

    setProjectId(getProjectId());
    setProject(getProject());
    setName(getName());
    setDescription(getDescription());
    setTemplateFlag(getTemplateFlag());
    setPaused(isPaused());
    BuildTypes templates = getTemplates();
    if (templates != null) {
      setTemplates(templates.initializeSubmittedFromUsual());
    }
    setVcsRootEntries(getVcsRootEntries());
    setParameters(getParameters());
    setSteps(getSteps());
    setFeatures(getFeatures());
    setTriggers(getTriggers());
    PropEntitiesSnapshotDep snapshotDependencies = getSnapshotDependencies();
    if (snapshotDependencies != null){
      if (snapshotDependencies.propEntities != null){
        for (PropEntitySnapshotDep dep : snapshotDependencies.propEntities) {
          if (dep.sourceBuildType != null){
            dep.sourceBuildType.initializeSubmittedFromUsual();
          }
        }
      }
      setSnapshotDependencies(snapshotDependencies);
    }
    PropEntitiesArtifactDep artifactDependencies = getArtifactDependencies();
    if (artifactDependencies != null){
      if (artifactDependencies.propEntities != null){
        for (PropEntityArtifactDep dep : artifactDependencies.propEntities) {
          if (dep.sourceBuildType != null){
            dep.sourceBuildType.initializeSubmittedFromUsual();
          }
        }
      }
      setArtifactDependencies(artifactDependencies);
    }
    setAgentRequirements(getAgentRequirements());
    setSettings(getSettings());
    return this;
  }

  @NotNull
  public BuildTypeOrTemplate createNewBuildTypeFromPosted(@NotNull final ServiceLocator serviceLocator) {
    SProject project;
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.project == null) {
      if (submittedParams.projectId == null) {
        throw new BadRequestException("Build type creation request should contain project node.");
      }
      //noinspection ConstantConditions
      project = serviceLocator.findSingletonService(ProjectManager.class).findProjectByExternalId(submittedParams.projectId);
      if (project == null) {
        throw new BadRequestException("Cannot find project with id '" + submittedParams.projectId + "'.");
      }
    } else {
      //noinspection ConstantConditions
      project = submittedParams.project.getProjectFromPosted(serviceLocator.findSingletonService(ProjectFinder.class));
    }

    if (StringUtil.isEmpty(submittedParams.name)) {
      throw new BadRequestException("When creating a build type, non empty name should be provided.");
    }

    final BuildTypeOrTemplate resultingBuildType = createEmptyBuildTypeOrTemplate(serviceLocator, project, submittedParams.name);

    try {
      fillBuildTypeOrTemplate(() -> resultingBuildType, serviceLocator);
    } catch (Exception e) {
      //error on filling the build type, should not preserve the created empty build type
      AuthorityHolder authorityHolder = myBeanContext.getSingletonService(SecurityContext.class).getAuthorityHolder();
      resultingBuildType.remove((SUser)authorityHolder.getAssociatedUser(), resultingBuildType.isBuildType() ? "Removing broken build configuration" : "Removing broken template");
      throw e;
    }

    return resultingBuildType;
  }

  @NotNull
  private BuildTypeOrTemplate createEmptyBuildTypeOrTemplate(final @NotNull ServiceLocator serviceLocator, final @NotNull SProject project, final @NotNull String name) {
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.templateFlag == null || !submittedParams.templateFlag) {
      return new BuildTypeOrTemplate(project.createBuildType(getIdForBuildType(serviceLocator, project, name), name));
    } else {
      return new BuildTypeOrTemplate(project.createBuildTypeTemplate(getIdForBuildType(serviceLocator, project, name), name));
    }
  }

  public boolean isSimilar(@Nullable final BuildType sourceBuildType) {
    SubmitedParameters submittedParams = mySubmitted.get();
    return sourceBuildType != null &&
           (Objects.equals(submittedParams.id, sourceBuildType.mySubmitted.get().id) || Objects.equals(submittedParams.internalId, sourceBuildType.mySubmitted.get().internalId));
  }

  private interface BuildTypeOrTemplatePatcher {
    @NotNull
    BuildTypeOrTemplate getBuildTypeOrTemplate();
  }

  /**
   * @param buildTypeOrTemplatePatcher provider of the build type to patch. Build type/template will only be retrieved if patching is necessary
   * @return true if there were modification attempts
   */
  private boolean fillBuildTypeOrTemplate(final @NotNull BuildTypeOrTemplatePatcher buildTypeOrTemplatePatcher, final @NotNull ServiceLocator serviceLocator) {
    boolean result = false;
    SubmitedParameters submittedParams = mySubmitted.get();
    if (submittedParams.description != null) {
      result = true;
      buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().setDescription(submittedParams.description);
    }
    if (submittedParams.paused != null) {
      if (buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType() == null) {
        throw new BadRequestException("Cannot set paused state for a template");
      }
//check if it is already paused      if (Boolean.valueOf(submittedPaused) ^ buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType().isPaused())
      result = true;
      buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType().setPaused(Boolean.valueOf(submittedParams.paused),
                                                                                   serviceLocator.getSingletonService(UserFinder.class).getCurrentUser(),
                                                                                   TeamCityProperties.getProperty("rest.defaultActionComment"));
    }

    if (submittedParams.templates != null) {
      if (buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType() == null) {
        throw new BadRequestException("Cannot set templates for a template");
      }
      try {
        //noinspection ConstantConditions
        List<BuildTypeOrTemplate> templates = submittedParams.templates.getFromPosted(serviceLocator.findSingletonService(BuildTypeFinder.class));
        BuildTypeOrTemplate.setTemplates(buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType(), templates, false);
      } catch (BadRequestException e) {
        throw new BadRequestException("Error retrieving submitted templates: " + e.getMessage(), e);
      }
      result = true;
    } else if (submittedParams.template != null) {
      if (buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType() == null) {
        throw new BadRequestException("Cannot set template for a template");
      }
      final BuildTypeOrTemplate templateFromPosted;
      try {
        //noinspection ConstantConditions
        templateFromPosted = submittedParams.template.getBuildTypeFromPosted(serviceLocator.findSingletonService(BuildTypeFinder.class));
      } catch (BadRequestException e) {
        throw new BadRequestException("Error retrieving submitted template: " + e.getMessage(), e);
      }
      if (templateFromPosted.getTemplate() == null) {
        throw new BadRequestException("'template' field should reference a template, not build type");
      }
      result = true;
      buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getBuildType().attachToTemplate(templateFromPosted.getTemplate());
    }

    BuildTypeSettingsEx buildTypeSettings = buildTypeOrTemplatePatcher.getBuildTypeOrTemplate().getSettingsEx();
    if (submittedParams.vcsRootEntries != null) {
      boolean updated = submittedParams.vcsRootEntries.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.parameters != null) {
      boolean updated = submittedParams.parameters.setTo(buildTypeOrTemplatePatcher.getBuildTypeOrTemplate(), serviceLocator);
      result = result || updated;
    }
    if (submittedParams.steps != null) {
      boolean updated = submittedParams.steps.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.features != null) {
      boolean updated = submittedParams.features.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.triggers != null) {
      boolean updated = submittedParams.triggers.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.snapshotDependencies != null) {
      boolean updated = submittedParams.snapshotDependencies.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.artifactDependencies != null) {
      boolean updated = submittedParams.artifactDependencies.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.agentRequirements != null) {
      boolean updated = submittedParams.agentRequirements.setToBuildType(buildTypeSettings, serviceLocator);
      result = result || updated;
    }
    if (submittedParams.settings != null && submittedParams.settings.properties != null) {
      //need to remove all settings if submittedSettings.properties == null???
      for (Property property : submittedParams.settings.properties) {
        try {
          property.addTo(new BuildTypeRequest.BuildTypeSettingsEntityWithParams(buildTypeOrTemplatePatcher.getBuildTypeOrTemplate()), serviceLocator);
          result = true;
        } catch (java.lang.UnsupportedOperationException e) {  //can be thrown from EditableBuildTypeCopy
          LOG.debug("Error setting property '" + property.name + "' to value '" + property.value + "': " + e.getMessage());
        }
      }
    }
    if (submittedParams.type != null) {
      //this overrides setting submitted via "settings"
      String previousValue = buildTypeSettings.getOption(BuildTypeOptions.BT_BUILD_CONFIGURATION_TYPE);

      boolean modified;
      try {
        String newValue = TypedFinderBuilder.getEnumValue(submittedParams.type, BuildTypeOptions.BuildConfigurationType.class).name();
        modified = !previousValue.equalsIgnoreCase(newValue);
        if (modified) {
          buildTypeSettings.setOption(BuildTypeOptions.BT_BUILD_CONFIGURATION_TYPE, newValue);
        }
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Could not set type to value '" + submittedParams.type + "'. Error: " + e.getMessage());
      }
      result = result || modified;
    }
    return result;
  }

  @NotNull
  public String getIdForBuildType(@NotNull final ServiceLocator serviceLocator, @NotNull SProject project, @NotNull final String name) {
    if (mySubmitted.get().id != null) {
      return mySubmitted.get().id;
    }
    return serviceLocator.getSingletonService(BuildTypeIdentifiersManager.class).generateNewExternalId(project.getExternalId(), name, null);
  }

  public static boolean shouldRestrictSettingsViewing(final @NotNull BuildTypeSettings buildType, final @NotNull PermissionChecker permissionChecker) {
    if (TeamCityProperties.getBooleanOrTrue("rest.beans.buildType.checkPermissions")) {
      return !permissionChecker.isPermissionGranted(Permission.VIEW_BUILD_CONFIGURATION_SETTINGS, buildType.getProject().getProjectId());
    }
    return false;
  }

  @Nullable
  private <T> T check(@Nullable T t) {
    if (canViewSettings) {
      return t;
    } else {
      return null;
    }
  }

  @NotNull
  public static ParametersPersistableEntity createEntity(@NotNull final BuildTypeOrTemplate buildType) {
    return new BuildTypeEntityWithParameters(buildType);
  }

  private static class BuildTypeEntityWithParameters extends InheritableUserParametersHolderEntityWithParameters
    implements ParametersPersistableEntity {
    @NotNull private final BuildTypeOrTemplate myBuildType;

    public BuildTypeEntityWithParameters(@NotNull final BuildTypeOrTemplate buildType) {
      super(buildType.getSettingsEx());
      myBuildType = buildType;
    }

    @Override
    public void persist(@NotNull String description) {
      myBuildType.persist(description);
    }

    @Nullable
    @Override
    public Boolean isInherited(@NotNull final String paramName) {
      Parameter ownParameter = getOwnParameter(paramName);
      if (ownParameter == null) return true;
      // might need to add check for read-only parameter here...
      return false;
    }
  }

  private static class SubmitedParameters {
    private String id;
    private String internalId;
    private String locator;
    private Boolean inherited;
    @Nullable
    private String projectId;
    @Nullable
    private Project project;
    @Nullable
    private String name;
    @Nullable
    private String description;
    @Nullable
    private Boolean templateFlag;
    @Nullable
    private String type;
    @Nullable
    private Boolean paused;
    @Nullable
    private BuildType template;
    @Nullable
    private BuildTypes templates;
    @Nullable
    private VcsRootEntries vcsRootEntries;
    @Nullable
    private Properties parameters;
    @Nullable
    private PropEntitiesStep steps;
    @Nullable
    private PropEntitiesFeature features;
    @Nullable
    private PropEntitiesTrigger triggers;
    @Nullable 
    private PropEntitiesSnapshotDep snapshotDependencies;
    @Nullable
    private PropEntitiesArtifactDep artifactDependencies;
    @Nullable
    private PropEntitiesAgentRequirement agentRequirements;
    @Nullable
    private Properties settings;
  }
}
