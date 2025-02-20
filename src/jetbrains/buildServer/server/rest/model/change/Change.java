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

import java.util.Collection;
import java.util.Date;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import jetbrains.buildServer.Used;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.change.ChangeUtil;
import jetbrains.buildServer.server.rest.data.change.SVcsModificationOrChangeDescriptor;
import jetbrains.buildServer.server.rest.data.finder.impl.ChangeFinder;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.errors.NotFoundException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.Items;
import jetbrains.buildServer.server.rest.model.Properties;
import jetbrains.buildServer.server.rest.model.Util;
import jetbrains.buildServer.server.rest.model.user.User;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelExperimental;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.ChangeDescriptor;
import jetbrains.buildServer.serverSide.WebLinks;
import jetbrains.buildServer.serverSide.auth.AuthUtil;
import jetbrains.buildServer.serverSide.auth.SecurityContext;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.ChangeStatusProvider;
import jetbrains.buildServer.vcs.RelationType;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.impl.VcsModificationEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: Yegor Yarko
 * Date: 12.04.2009
 */
@XmlRootElement(name = "change")
@XmlType(name = "change", propOrder = {
  "id",
  "version",
  "internalVersion",
  "username", // deprecated
  "date",
  "registrationDate",
  "personal",
  "href",
  "webUrl",
  "comment",
  "user",     // deprecated
  "type",
  "snapshotDependencyLink",
  "fileChanges",
  "vcsRootInstance",
  "parentChanges",
  "parentRevisions",
  "attributes",
  "storesProjectSettings",
  "status",
  "mergedInfo",
  "commiter"
})
@ModelDescription(
    value = "Represents a VCS change (commit).",
    externalArticleLink = "https://www.jetbrains.com/help/teamcity/change.html",
    externalArticleName = "Change"
)
public class Change {
  protected SVcsModification myModification;
  @Nullable
  protected ChangeDescriptor myDescriptor;
  protected ApiUrlBuilder myApiUrlBuilder;
  protected WebLinks myWebLinks;
  @NotNull private Fields myFields;
  @NotNull private BeanContext myBeanContext;
  /**
   * These are used only when posting a link to the change
   */
  private String submittedLocator;
  private Long submittedId;
  private Boolean submittedPersonal;

  @Used("javax.xml")
  public Change() {
  }

  public Change(@NotNull SVcsModification modification, final @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    this(new SVcsModificationOrChangeDescriptor(modification), fields, beanContext);
  }

  public Change(SVcsModificationOrChangeDescriptor modificationOrDescriptor, final @NotNull Fields fields, @NotNull final BeanContext beanContext) {
    myDescriptor = modificationOrDescriptor.getChangeDescriptor();
    myModification = modificationOrDescriptor.getSVcsModification();
    myFields = fields;
    myBeanContext = beanContext;
    myApiUrlBuilder = myBeanContext.getApiUrlBuilder();
    myWebLinks = myBeanContext.getSingletonService(WebLinks.class);
  }

  public static String getFieldValue(@NotNull SVcsModificationOrChangeDescriptor modificationOrDescriptor, final String field) {
    return getFieldValue(modificationOrDescriptor.getSVcsModification(), field);
  }

  public static String getFieldValue(final SVcsModification vcsModification, final String field) {
    if ("id".equals(field)) {
      return String.valueOf(vcsModification.getId());
    } else if ("version".equals(field)) {
      return vcsModification.getDisplayVersion();
    } else if ("username".equals(field)) {
      return vcsModification.getUserName();
    } else if ("date".equals(field)) {
      return Util.formatTime(vcsModification.getVcsDate());
    } else if ("personal".equals(field)) {
      return String.valueOf(vcsModification.isPersonal());
    } else if ("comment".equals(field)) {
      return escapeNonPrintedCharacters(vcsModification.getDescription());
    } else if ("registrationDate".equals(field)) { //not documented
      return Util.formatTime(vcsModification.getRegistrationDate());
    } else if ("versionControlName".equals(field)) { //not documented
      return vcsModification.getVersionControlName();
    } else if ("internalVersion".equals(field)) { //not documented
      return vcsModification.getVersion();
    }
    throw new NotFoundException("Field '" + field + "' is not supported. Supported fields are: 'id', 'version', 'username', 'date', 'personal', 'comment'.");
  }

  private static boolean isVersionedSettings(@NotNull final SVcsModification change) {
    final boolean[] isVersionedSettingsChange = new boolean[]{false};
    ((VcsModificationEx)change).consumeRelations((s, relationType) -> {
      if (relationType == RelationType.SETTINGS_AFFECT_BUILDS) {
        isVersionedSettingsChange[0] = true;
      }
    });
    return isVersionedSettingsChange[0];
  }

  @XmlAttribute
  public Long getId() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("id"), myModification.getId());
  }

  public void setId(Long id) {
    submittedId = id;
  }

  @XmlAttribute
  public String getVersion() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("version"), myModification.getDisplayVersion());
  }

  @Used
  @XmlAttribute
  public String getInternalVersion() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("internalVersion", false, false), () -> myModification.getVersion());
  }

  @XmlAttribute
  public Boolean getPersonal() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("personal"), myModification.isPersonal());
  }

  public void setPersonal(Boolean value) {
    submittedPersonal = value;
  }

  @XmlAttribute
  public String getHref() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("href"), myApiUrlBuilder.getHref(myModification));
  }

  @XmlAttribute
  public String getWebUrl() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("webUrl"), myWebLinks.getChangeUrl(myModification.getId(), myModification.isPersonal()));
  }

  /**
   * @deprecated use commiter.vcsUsername instead
   */
  @XmlAttribute
  public String getUsername() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("username"), myModification.getUserName());
  }

  @XmlAttribute
  public String getDate() {
    final Date vcsDate = myModification.getVcsDate();
    return ValueWithDefault.decideDefault(myFields.isIncluded("date"), Util.formatTime(vcsDate));
  }

  @Used
  @XmlAttribute
  public String getRegistrationDate() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("registrationDate", false, false), () -> Util.formatTime(myModification.getRegistrationDate()));
  }

  @XmlElement
  public String getComment() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("comment", false), () -> escapeNonPrintedCharacters(myModification.getDescription()));
  }

  /**
   * Exeprimental, is there a better way to represent action availability across REST?
   */
  @XmlAttribute(name = "canEditComment")
  public Boolean getCanEditComment() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("canEditComment", false, false),
      () -> {
        SecurityContext context = myBeanContext.getSingletonService(SecurityContext.class);
        return AuthUtil.hasPermissionToEditModification(context.getAuthorityHolder(), myModification);
      }
    );
  }

  @XmlElement
  public String getType() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("type", false), () -> {
      return myDescriptor != null ? myDescriptor.getType() : null;
    });
  }

  @XmlElement(name="snapshotDependencyLink")
  public SnapshotDependencyLink getSnapshotDependencyLink() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("snapshotDependencyLink", false), () -> {
      if(myDescriptor == null)
        return null;

      return ChangeUtil.getSnapshotDependencyLink(myDescriptor, myFields.getNestedField("snapshotDependencyLink"), myBeanContext);
    });
  }

  @NotNull
  private static String escapeNonPrintedCharacters(final String str) {
    // Super-quick temporary workaround for TW-65005
    return StringUtil.replaceInvalidXmlChars(str);
  }

  /**
   * @deprecated use commiter.users instead
   */
  @XmlElement(name = "user")
  public User getUser() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("user", false), () -> {
      final Collection<SUser> users = myModification.getCommitters();
      if (users.size() != 1) {
        return null;
      }
      return new User(users.iterator().next(), myFields.getNestedField("user"), myBeanContext);
    });
  }

  @XmlElement(name = "commiter")
  public Commiter getCommiter() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("commiter", false), () -> {
      final Collection<SUser> users = myModification.getCommitters();
      return new Commiter(myFields.getNestedField("commiter"), myModification.getUserName(), users, myBeanContext);
    });
  }

  @XmlElement(name = "files")
  public FileChanges getFileChanges() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("files", false), () -> new FileChanges(myModification.getChanges(), myFields.getNestedField("files")));
  }

  @XmlElement(name = "vcsRootInstance")
  public VcsRootInstance getVcsRootInstance() {
    return myModification.isPersonal()
           ? null
           : ValueWithDefault.decideDefault(myFields.isIncluded("vcsRootInstance", false),
                                            new VcsRootInstance(myModification.getVcsRoot(), myFields.getNestedField("vcsRootInstance"), myBeanContext));
  }

  @Used
  @XmlElement(name = "parentChanges")
  public Changes getParentChanges() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("parentChanges", false, false),
      () -> Changes.fromSVcsModifications(myModification.getParentModifications(), null, myFields.getNestedField("parentChanges"), myBeanContext)
    );
  }

  @Used
  @XmlElement(name = "parentRevisions")
  public Items getParentRevisions() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("parentRevisions", false, false), () -> new Items(myModification.getParentRevisions()));
  }

  /**
   * experimental use only
   */
  @XmlElement(name = "attributes")
  public Properties getAttributes() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("attributes", false, false),
                                          () -> new Properties(myModification.getAttributes(), null, myFields.getNestedField("attributes"), myBeanContext));
  }

  @XmlElement(name = "status")
  public ChangeStatus getStatus() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("status", false, false), () -> {
      ChangeStatusProvider statusProvider = myBeanContext.getSingletonService(ChangeStatusProvider.class);
      jetbrains.buildServer.vcs.ChangeStatus mergedStatus = statusProvider.getMergedChangeStatus(myModification);

      return new ChangeStatus(mergedStatus, myFields.getNestedField("status"), myBeanContext);
    });
  }

  @ModelExperimental
  @XmlElement(name = "mergedInfo")
  public ChangeMergedInfo getMergedInfo() {
    return ValueWithDefault.decideDefault(
      myFields.isIncluded("mergedInfo", false, false), () -> {
        ChangeStatusProvider statusProvider = myBeanContext.getSingletonService(ChangeStatusProvider.class);
        jetbrains.buildServer.vcs.ChangeStatus mergedStatus = statusProvider.getMergedChangeStatus(myModification);

        return new ChangeMergedInfo(mergedStatus, myFields.getNestedField("mergedInfo"), myBeanContext);
      }
    );
  }

  /**
   * experimental
   */
  @Used
  @XmlAttribute(name = "storesProjectSettings")
  public Boolean getStoresProjectSettings() {
    return ValueWithDefault.decideDefault(myFields.isIncluded("storesProjectSettings", false, false),
                                          () -> isVersionedSettings(myModification));
  }

  @XmlAttribute
  public String getLocator() {
    return null;
  }

  public void setLocator(final String locator) {
    submittedLocator = locator;
  }

  @NotNull
  public SVcsModification getChangeFromPosted(final ChangeFinder changeFinder) {
    String locatorText;
    if (submittedId != null) {
      if (submittedLocator != null) {
        throw new BadRequestException("Both 'locator' and 'id' attributes are specified. Only one should be present.");
      }
      final Locator locator = Locator.createEmptyLocator().setDimension(ChangeFinder.DIMENSION_ID, String.valueOf(submittedId));
      if (submittedPersonal != null && submittedPersonal) {
        locator.setDimension(ChangeFinder.PERSONAL, "true");
      }
      locatorText = locator.getStringRepresentation();
    } else {
      if (submittedLocator == null) {
        throw new BadRequestException("No change specified. Either 'id' or 'locator' attribute should be present.");
      }
      locatorText = submittedLocator;
    }

    return changeFinder.getItem(locatorText).getSVcsModification();
  }
}
