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

package jetbrains.buildServer.server.rest.model.change;

import com.intellij.openapi.util.text.StringUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.server.rest.APIController;
import jetbrains.buildServer.server.rest.data.DataProvider;
import jetbrains.buildServer.server.rest.data.PermissionChecker;
import jetbrains.buildServer.server.rest.data.util.LocatorUtil;
import jetbrains.buildServer.server.rest.errors.InvalidStateException;
import jetbrains.buildServer.server.rest.errors.NotFoundException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.Items;
import jetbrains.buildServer.server.rest.model.Properties;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.RepositoryVersion;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.serverSide.impl.LogUtil;
import jetbrains.buildServer.vcs.SingleVersionRepositoryStateAdapter;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsManager;
import jetbrains.buildServer.vcs.VcsRootInstanceEx;
import jetbrains.buildServer.vcs.impl.RepositoryStateManager;
import jetbrains.vcs.api.services.tc.VcsMappingElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.serverSide.impl.projectSources.SmallPatchCache.LOG;

/**
 * @author Yegor.Yarko
 *         Date: 16.04.2009
 */
@XmlRootElement(name = "vcs-root-instance")
@XmlType(name = "vcs-root-instance", propOrder = {"id", "vcsRootId", "vcsRootInternalId", "name", "vcsName",
  "modificationCheckInterval", "commitHookMode", "lastVersion", "lastVersionInternal", "href",
  "parent", "status", "repositoryState", "properties", "repositoryIdStrings"})
@SuppressWarnings("PublicField")
@ModelDescription(
    value = "Represents a relation between a VCS root and unique settings set for this root.",
    externalArticleLink = "https://www.jetbrains.com/help/teamcity/configuring-vcs-settings.html#VCS+Settings+Overview",
    externalArticleName = "VCS Settings"
)
public class VcsRootInstance {
  public static final String LAST_VERSION_INTERNAL = "lastVersionInternal";
  public static final String LAST_VERSION = "lastVersion";
  public static final String COMMIT_HOOK_MODE = "commitHookMode";
  private jetbrains.buildServer.vcs.VcsRootInstance myRoot;
  private Fields myFields;
  private BeanContext myBeanContext;
  private final boolean canViewSettings;

  @XmlAttribute
  public String id;

  @XmlAttribute
  public String name;

  @XmlAttribute(name = "vcs-root-id")
  public String vcsRootId;

  @XmlAttribute(name = "vcsRootInternalId")
  public String vcsRootInternalId;

  @XmlAttribute
  public String vcsName;

  @XmlAttribute
  public Integer modificationCheckInterval;

  /**
   * experimental
   */
  @XmlAttribute
  public Boolean commitHookMode;

  @XmlAttribute
  public String href;

  /**
   * Used only when creating new VCS roots
   * @deprecated Specify project element instead
   */
  @Deprecated
  @XmlAttribute
  public String projectLocator;


  public VcsRootInstance() {
    canViewSettings = true;
  }

  public VcsRootInstance(final jetbrains.buildServer.vcs.VcsRootInstance root, final @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    myRoot = root;

    myFields = fields;
    myBeanContext = beanContext;

    id = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("id"), String.valueOf(root.getId()));
    name = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("name"), root.getName());
    href = ValueWithDefault.decideDefault(fields.isIncluded("href"), beanContext.getApiUrlBuilder().getHref(root));

    vcsRootId = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("vcs-root-id", true, true), root.getParent().getExternalId());
    final boolean includeInternalId = TeamCityProperties.getBoolean(APIController.INCLUDE_INTERNAL_ID_PROPERTY_NAME);
    vcsRootInternalId = ValueWithDefault.decideIncludeByDefault(
      fields.isIncluded("vcsRootInternalId", includeInternalId, includeInternalId), String.valueOf(root.getParentId()));

    final PermissionChecker permissionChecker = beanContext.getServiceLocator().findSingletonService(PermissionChecker.class);
    assert permissionChecker != null;
    canViewSettings = !VcsRoot.shouldRestrictSettingsViewing(root.getParent(), permissionChecker);

    vcsName = ValueWithDefault.decideDefault(fields.isIncluded("vcsName", false), root.getVcsName());
    modificationCheckInterval = ValueWithDefault.decideDefault(fields.isIncluded("modificationCheckInterval", false), (int)root.getEffectiveModificationCheckInterval());
    commitHookMode = ValueWithDefault.decideDefault(fields.isIncluded(COMMIT_HOOK_MODE, false, false), !((VcsRootInstanceEx)root).isPollingMode());
  }

  @XmlAttribute
  public String getLastVersion() {
    return check(ValueWithDefault.decideDefault(myFields.isIncluded("lastVersion", false), new ValueWithDefault.Value<String>() {
      @Nullable
      public String get() {
        final RepositoryVersion currentRevision = myRoot.getLastUsedRevision();
        return currentRevision != null ? currentRevision.getDisplayVersion() : null;
      }
    }));
  }

  @XmlAttribute
  public String getLastVersionInternal() {
    return check(ValueWithDefault.decideDefault(myFields.isIncluded("lastVersionInternal", false, TeamCityProperties.getBoolean("rest.internalMode")),
                                                new ValueWithDefault.Value<String>() {
                                                  @Nullable
                                                  public String get() {
                                                    final RepositoryVersion currentRevision = myRoot.getLastUsedRevision();
                                                    return currentRevision != null ? currentRevision.getVersion() : null;
                                                  }
                                                }));
  }

  @XmlElement(name = "vcs-root")
  public VcsRoot getParent() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("vcs-root", false), new VcsRoot(myRoot.getParent(), myFields.getNestedField("vcs-root"), myBeanContext));
  }

  @XmlElement(name = "repositoryState")
  public RepositoryState getRepositoryState() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("repositoryState", false, false), () ->
      check(new RepositoryState(((VcsRootInstanceEx)myRoot).getLastUsedState(), myFields.getNestedField("repositoryState"), myBeanContext)));
  }

  /**
   * experimental
   */
  @XmlElement(name = "status")
  public VcsStatus getStatus() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("status", false), () ->
      check(new VcsStatus((VcsRootInstanceEx)myRoot, myFields.getNestedField("status", Fields.NONE, Fields.SHORT), myBeanContext)));
  }

  @XmlElement
  public Properties getProperties() {
    return check(ValueWithDefault.decideDefault(myFields.isIncluded("properties", false),
                                                () -> {
                                                  Map<String, String> properties = new LinkedHashMap<>(myRoot.getProperties());
                                                  properties.remove("teamcity:branchSpec"); //TW-56449
                                                  return new Properties(properties, null, myFields.getNestedField("properties", Fields.NONE, Fields.LONG), myBeanContext);
                                                }));
  }

  @XmlElement
  public Items getRepositoryIdStrings() {
    return check(ValueWithDefault.decideDefault(myFields.isIncluded("repositoryIdStrings", false, false), new ValueWithDefault.Value<Items>() {
      @Nullable
      @Override
      public Items get() {
        ArrayList<String> result = new ArrayList<>();
        try {
          Collection<VcsMappingElement> vcsMappingElements = VcsRoot.getRepositoryMappings(myRoot, myBeanContext.getSingletonService(VcsManager.class));
          for (VcsMappingElement vcsMappingElement : vcsMappingElements) {
            result.add(vcsMappingElement.getTo());
          }
          return new Items(result);
        } catch (Exception e) {
          LOG.debug("Error while retrieving mapping for VCS root " + LogUtil.describe(myRoot) + ", skipping " + "repositoryIdStrings" + " in root details", e);
          //ignore
        }
        return null;
      }
    }));
  }

  public static String getFieldValue(final jetbrains.buildServer.vcs.VcsRootInstance rootInstance,
                                     final String field,
                                     final DataProvider dataProvider) {
    if ("id".equals(field)) {
       return String.valueOf(rootInstance.getId());
     } else if ("name".equals(field)) {
       return rootInstance.getName();
     } else if ("vcsName".equals(field)) {
       return rootInstance.getVcsName();
     } else if ("projectInternalId".equals(field)) { //Not documented, do we actually need this?
       return rootInstance.getParent().getScope().getOwnerProjectId();
     } else if ("projectId".equals(field)) { //Not documented
       final SProject projectOfTheRoot = VcsRoot.getProjectByRoot(rootInstance.getParent());
       return projectOfTheRoot != null ? projectOfTheRoot.getExternalId() : "";
     } else if ("repositoryMappings".equals(field)) { //Not documented
       try {
         return String.valueOf(VcsRoot.getRepositoryMappings(rootInstance, dataProvider.getVcsManager()));  //todo: fix presentation. Curently this returns "[MappingElement{..."
       } catch (VcsException e) {
         throw new InvalidStateException("Error retrieving mapping", e);
       }
    } else if (LAST_VERSION.equals(field)) {
      final RepositoryVersion currentRevision = rootInstance.getLastUsedRevision();
      return currentRevision != null ? currentRevision.getDisplayVersion() : ""; //if we return null, status code for this case is 204/not changed and cached value can be used
    } else if (LAST_VERSION_INTERNAL.equals(field)) {
      final RepositoryVersion currentRevision = rootInstance.getLastUsedRevision();
      return currentRevision != null ? currentRevision.getVersion() : "";
    } else if ("currentVersion".equals(field)) {
      try {
        return rootInstance.getCurrentRevision().getDisplayVersion();
      } catch (VcsException e) {
        throw new InvalidStateException("Error while getting current revision: ", e);
      }
    } else if ("currentVersionInternal".equals(field)) {
      try {
        return  rootInstance.getCurrentRevision().getVersion();
      } catch (VcsException e) {
        throw new InvalidStateException("Error while getting current revision: ", e);
      }
    } else if (COMMIT_HOOK_MODE.equals(field)) {
      return String.valueOf(!((VcsRootInstanceEx)rootInstance).isPollingMode());
    }
    throw new NotFoundException(
      "Field '" + field + "' is not supported. Supported are: id, name, vcsName, " + LAST_VERSION + ", " + LAST_VERSION_INTERNAL + ", currentVersion, currentVersionInternal.");
  }

  public static void setFieldValue(final jetbrains.buildServer.vcs.VcsRootInstance rootInstance,
                                   final String field,
                                   final String newValue,
                                   @NotNull final BeanContext beanContext) {
    if (LAST_VERSION_INTERNAL.equals(field) || (LAST_VERSION.equals(field) && StringUtil.isEmpty(newValue))) {
      if (!StringUtil.isEmpty(newValue)) {
        beanContext.getSingletonService(RepositoryStateManager.class).setRepositoryState(rootInstance, new SingleVersionRepositoryStateAdapter(newValue));
        Loggers.VCS.info("Repository state is set to \"" + newValue+ "\" via REST API call for " + rootInstance.describe(false) + " by " + beanContext.getSingletonService(PermissionChecker.class).getCurrentUserDescription());
      } else {
        beanContext.getSingletonService(RepositoryStateManager.class).setRepositoryState(rootInstance, new SingleVersionRepositoryStateAdapter((String)null));
        Loggers.VCS.info("Repository state is reset via REST API call for " + rootInstance.describe(false) + " by " + beanContext.getSingletonService(PermissionChecker.class).getCurrentUserDescription());
      }
      return;
    } else if (COMMIT_HOOK_MODE.equals(field)) {
      boolean pollingMode = !LocatorUtil.getStrictBooleanOrReportError(newValue);
      ((VcsRootInstanceEx)rootInstance).setPollingMode(pollingMode);
      Loggers.VCS.info("Poling mode is set to \"" + pollingMode + "\" via REST API call for " + rootInstance.describe(false) + " by " + beanContext.getSingletonService(PermissionChecker.class).getCurrentUserDescription());
      return;
    }
    throw new NotFoundException("Setting of field '" + field + "' is not supported. Supported are: " + LAST_VERSION_INTERNAL + ", " + COMMIT_HOOK_MODE);
  }

  @Nullable
  private <T> T check(@Nullable T t) {
    if (canViewSettings) {
      return t;
    } else {
      return null;
    }
  }
}

