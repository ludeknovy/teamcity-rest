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

package jetbrains.buildServer.server.rest.request;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import jetbrains.buildServer.controllers.fakes.FakeHttpServletRequest;
import jetbrains.buildServer.groups.SUserGroup;
import jetbrains.buildServer.groups.UserGroup;
import jetbrains.buildServer.server.rest.data.finder.BaseFinderTest;
import jetbrains.buildServer.server.rest.errors.AuthorizationFailedException;
import jetbrains.buildServer.server.rest.errors.NotFoundException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.build.Build;
import jetbrains.buildServer.server.rest.model.user.PermissionAssignment;
import jetbrains.buildServer.server.rest.model.user.PermissionAssignments;
import jetbrains.buildServer.server.rest.model.user.User;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.auth.RoleScope;
import jetbrains.buildServer.serverSide.auth.TwoFactorPasswordManager;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import jetbrains.buildServer.serverSide.impl.auth.SecurityContextImpl;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.util.TestFor;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Yegor.Yarko
 * @date 05/04/2016
 */
public class UserRequestTest extends BaseFinderTest<UserGroup> {
  private UserRequest myRequest;

  @Override
  @BeforeMethod
  public void setUp() throws Exception {
    super.setUp();
    myRequest = new UserRequest();
    myRequest.initForTests(BaseFinderTest.getBeanContext(myFixture));
  }


  @Test
  void testBasic1() throws Throwable {
    myFixture.getServerSettings().setPerProjectPermissionsEnabled(true);

    final SUser user1 = createUser("user1");
    final SUser user2 = createUser("user2");

    SecurityContextImpl securityContext = myFixture.getSecurityContext();

    user2.addRole(RoleScope.globalScope(), getProjectAdminRole());

    securityContext.runAs(user2, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        assertNotNull(myRequest.serveUser("username:user1", Fields.LONG.getFieldsSpec()));
        assertNotNull(myRequest.serveUsers(null, Fields.LONG.getFieldsSpec()));
        assertEquals(1, myRequest.getGroups("username:user1", Fields.LONG.getFieldsSpec()).groups.size());
        assertNotNull(myRequest.serveUserProperties("username:user1", Fields.LONG.getFieldsSpec()));
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        assertNotNull(myRequest.serveUser("username:user1", Fields.LONG.getFieldsSpec()));
        assertEquals(1, myRequest.getGroups("username:user1", Fields.LONG.getFieldsSpec()).groups.size());
        assertNotNull(myRequest.serveUserProperties("username:user1", Fields.LONG.getFieldsSpec()));
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        checkException(NotFoundException.class, new Runnable() {
          @Override
          public void run() {
            myRequest.serveUser("username:user2", Fields.LONG.getFieldsSpec());
          }
        }, null);
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        checkException(NotFoundException.class, new Runnable() {
          @Override
          public void run() {
            myRequest.getGroups("username:user2", Fields.LONG.getFieldsSpec());
          }
        }, null);
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        checkException(NotFoundException.class, new Runnable() {
          @Override
          public void run() {
            myRequest.serveUserProperties("username:user2", Fields.LONG.getFieldsSpec());
          }
        }, null);
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        checkException(AuthorizationFailedException.class, new Runnable() {
          @Override
          public void run() {
            myRequest.serveUsers(null, Fields.LONG.getFieldsSpec());
          }
        }, null);
      }
    });

  }

  @Test
  @TestFor(issues = {"TW-44842"})
  void testUnauthorizedUsersList() throws Throwable {
    final SUser user1 = createUser("user1");
    final SUser user2 = createUser("user2");

    SecurityContextImpl securityContext = myFixture.getSecurityContext();

    user2.addRole(RoleScope.globalScope(), getProjectAdminRole());

    securityContext.runAs(user2, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        User result = myRequest.serveUser("id:" + user1.getId(), "$long,groups(group(users(user)))");
        assertNotNull(result);
        assertNotNull(result.getGroups());
        assertNotNull(result.getGroups().groups);
        assertEquals(1, result.getGroups().groups.size());
        assertNotNull(result.getGroups().groups.get(0).users);
        assertNotNull(result.getGroups().groups.get(0).users.users);
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        User result = myRequest.serveUser("username:user1", "$long,groups(group)");
        assertNotNull(result);
        assertNotNull(result.getGroups());
        assertNotNull(result.getGroups().groups);
        assertEquals(1, result.getGroups().groups.size());
      }
    });

    securityContext.runAs(user1, new SecurityContextEx.RunAsAction() {
      @Override
      public void run() throws Throwable {
        User result = myRequest.serveUser("username:user1", "$long,groups(group(users(user)))");
        assertNotNull(result);
        assertNotNull(result.getGroups());
        assertNotNull(result.getGroups().groups);
        assertEquals(1, result.getGroups().groups.size());
        assertNull(result.getGroups().groups.get(0).users); //on getting users, AuthorizationFailedException is thrown so users are not included
      }
    });
  }

  @Test
  void testUserEnityExposure() throws Throwable {
    myFixture.getServerSettings().setPerProjectPermissionsEnabled(true);

    final SUser user1 = createUser("user1");
    final SUser user2 = createUser("user2");

    //filling all user fields
    user1.updateUserAccount("user1", "Display Name1", "email1@domain.com");
    user2.updateUserAccount("user2", "Display Name2", "email2@domain.com");
    SUserGroup group1 = myFixture.createUserGroup("key1", "name1", "description");
    group1.addUser(user1);
    group1.addUser(user2);
    user1.addRole(RoleScope.globalScope(), getProjectViewerRole());
    user2.addRole(RoleScope.globalScope(), getProjectViewerRole());
    user1.setLastLoginTimestamp(new Date());
    user2.setLastLoginTimestamp(new Date());
    user1.setPassword("secret");
    user2.setPassword("secret");

    myFixture.getUserAvatarsManager().saveAvatar(user1, new BufferedImage(1, 1, 1));
    myFixture.getUserAvatarsManager().saveAvatar(user2, new BufferedImage(1, 1, 1));

    enable2FA(user1);
    enable2FA(user2);

    SecurityContextImpl securityContext = myFixture.getSecurityContext();

    user2.addRole(RoleScope.globalScope(), getProjectAdminRole());

    SFinishedBuild build10 = build().in(myBuildType).by(user1).finish();
    SFinishedBuild build20 = build().in(myBuildType).by(user2).finish();

    BuildRequest buildRequest = new BuildRequest();
    buildRequest.initForTests(BaseFinderTest.getBeanContext(myFixture));

    assertEquals(15, getSubEntitiesNames(User.class).size()); //if changed, the checks below should be changed

    final String fields = "triggered(user($long,hasPassword))";
    {
      Build build = buildRequest.serveBuild("id:" + build10.getBuildId(), fields, new FakeHttpServletRequest());
      // check that all is present
      User user = build.getTriggered().user;
      assertNotNull(user.getUsername());
      assertNotNull(user.getName());
      assertNotNull(user.getId());
      assertNotNull(user.getEmail());
      assertNotNull(user.getLastLogin());
      assertNotNull(user.getHref());
      assertNotNull(user.getProperties());
      assertNotNull(user.getRoles());
      assertNotNull(user.getGroups());
      assertNotNull(user.getHasPassword());
      assertNotNull(user.getAvatars());
      assertNotNull(user.getEnabled2FA());
      assertNull(user.getPassword());  //not included in response
      assertNull(user.getLocator());  //submit-only
      assertNull(user.getRealm()); //obsolete
    }

    {
      Build build = buildRequest.serveBuild("id:" + build20.getBuildId(), fields, new FakeHttpServletRequest());
      // check that all is present
      User user = build.getTriggered().user;
      assertNotNull(user.getUsername());
      assertNotNull(user.getName());
      assertNotNull(user.getId());
      assertNotNull(user.getEmail());
      assertNotNull(user.getLastLogin());
      assertNotNull(user.getHref());
      assertNotNull(user.getProperties());
      assertNotNull(user.getRoles());
      assertNotNull(user.getGroups());
      assertNotNull(user.getHasPassword());
      assertNotNull(user.getAvatars());
      assertNotNull(user.getEnabled2FA());
      assertNull(user.getPassword());  //not included in response
      assertNull(user.getLocator());  //submit-only
      assertNull(user.getRealm()); //obsolete
    }

    securityContext.runAs(user1, () -> {
      Build build = buildRequest.serveBuild("id:" + build10.getBuildId(), fields, new FakeHttpServletRequest());
      // check that all is present
      User user = build.getTriggered().user;
      assertNotNull(user.getUsername());
      assertNotNull(user.getName());
      assertNotNull(user.getId());
      assertNotNull(user.getEmail());
      assertNotNull(user.getLastLogin());
      assertNotNull(user.getHref());
      assertNotNull(user.getProperties());
      assertNotNull(user.getRoles());
      assertNotNull(user.getGroups());
      assertNotNull(user.getHasPassword());
      assertNotNull(user.getAvatars());
      assertNotNull(user.getEnabled2FA());
      assertNull(user.getPassword());
    });

    securityContext.runAs(user2, () -> {
      Build build = buildRequest.serveBuild("id:" + build10.getBuildId(), fields, new FakeHttpServletRequest());
      // check that all is present
      User user = build.getTriggered().user;
      assertNotNull(user.getUsername());
      assertNotNull(user.getName());
      assertNotNull(user.getId());
      assertNotNull(user.getEmail());
      assertNotNull(user.getLastLogin());
      assertNotNull(user.getHref());
      assertNotNull(user.getProperties());
      assertNotNull(user.getRoles());
      assertNotNull(user.getGroups());
      assertNotNull(user.getHasPassword());
      assertNotNull(user.getAvatars());
      assertNotNull(user.getEnabled2FA());
      assertNull(user.getPassword());
    });

    securityContext.runAs(user1, () -> {
      Build build = buildRequest.serveBuild("id:" + build20.getBuildId(), fields, new FakeHttpServletRequest());
      // check that all is present
      User user = build.getTriggered().user;
      assertNotNull(user.getUsername());
      assertNotNull(user.getName());
      assertNotNull(user.getId());
      assertNull(user.getEmail());
      assertNull(user.getLastLogin());
      assertNotNull(user.getHref());
      assertNull(user.getProperties());
      assertNull(user.getRoles());
      assertNull(user.getGroups());
      assertNull(user.getHasPassword());
      assertNull(user.getPassword());
      assertNotNull(user.getAvatars());
      assertNull(user.getEnabled2FA());
    });
  }

  @SuppressWarnings("ConstantConditions")
  @Test
  public void testPermissionsSecurity() throws Throwable {
    myFixture.getServerSettings().setPerProjectPermissionsEnabled(true);

    ProjectEx project1 = createProject("project1", "project1");
    ProjectEx project2 = createProject("project2", "project2");

    SUser user1 = createUser("user1");

    SUser user2 = createUser("user2");
    user2.addRole(RoleScope.globalScope(), getTestRoles().createRole(Permission.RUN_BUILD, Permission.AUTHORIZE_AGENT));
    user2.addRole(RoleScope.projectScope(project2.getProjectId()), getTestRoles().createRole(Permission.VIEW_PROJECT));
    user2.addRole(RoleScope.projectScope(project1.getProjectId()), getTestRoles().createRole(Permission.VIEW_PROJECT, Permission.REORDER_BUILD_QUEUE));

    myFixture.getSecurityContext().runAs(user1, () -> {
      checkException(NotFoundException.class, () -> myRequest.getPermissions("id:" + user2.getId(), null, null), "getting permissions of another user");
    });

    SUser user3 = createUser("user3");
    user3.addRole(RoleScope.globalScope(), getTestRoles().createRole(Permission.VIEW_USER_PROFILE, Permission.VIEW_ALL_USERS));
    user3.addRole(RoleScope.projectScope(project2.getProjectId()), getTestRoles().createRole(Permission.VIEW_PROJECT));

    myFixture.getSecurityContext().runAs(user3, () -> {
      PermissionAssignments permissions = myRequest.getPermissions("id:" + user2.getId(), null, null);
      List<PermissionAssignment> permissionAssignments = permissions.myPermissionAssignments;

      String message = describe(permissions);
      assertContains(message, permissionAssignments,
                     pa -> permissionEquals(pa, Permission.AUTHORIZE_AGENT) && pa.project == null && pa.isGlobalScope);
      assertContains(message, permissionAssignments,
                     pa -> permissionEquals(pa, Permission.REORDER_BUILD_QUEUE) && pa.project == null && pa.isGlobalScope);
      assertContains(message, permissionAssignments,
                     pa -> permissionEquals(pa, Permission.RUN_BUILD) && pa.project == null && pa.isGlobalScope);
      assertContains(message, permissionAssignments,
                     pa -> permissionEquals(pa, Permission.VIEW_PROJECT) && project2.getExternalId().equals(pa.project.id) && !pa.isGlobalScope);
      assertNotContains(message, permissionAssignments,
                        pa -> permissionEquals(pa, Permission.VIEW_PROJECT) && project1.getExternalId().equals(pa.project.id));
    });

    getUserModelEx().getGuestUser().addRole(RoleScope.projectScope(project2.getProjectId()), getTestRoles().createRole(Permission.RUN_BUILD));

    myFixture.getSecurityContext().runAs(getUserModelEx().getGuestUser(), () -> {
      PermissionAssignments permissions = myRequest.getPermissions("current", null, null);
      assertContains(describe(permissions), permissions.myPermissionAssignments,
                        pa -> permissionEquals(pa, Permission.RUN_BUILD) && project2.getExternalId().equals(pa.project.id));

      checkException(NotFoundException.class, () -> myRequest.getPermissions("id:" + user2.getId(), null, null), "getting permissions of another user");
    });

    myFixture.getSecurityContext().runAs(getUserModelEx().getSuperUser(), () -> {
      PermissionAssignments permissions = myRequest.getPermissions("current", null, null);
      assertContains(describe(permissions), permissions.myPermissionAssignments,
        pa -> permissionEquals(pa, Permission.EDIT_PROJECT) && pa.project == null);

      permissions = myRequest.getPermissions("id:" + user2.getId(), null, null);
      assertContains(describe(permissions), permissions.myPermissionAssignments,
                        pa -> permissionEquals(pa, Permission.VIEW_PROJECT) && project1.getExternalId().equals(pa.project.id));
      assertContains(describe(permissions), permissions.myPermissionAssignments,
                        pa -> permissionEquals(pa, Permission.AUTHORIZE_AGENT) && pa.project == null);
    });
  }

  private static <T> void assertContains(String message, List<T> elements, Predicate<T> predicate) {
    assertTrue(message, elements.stream().anyMatch(predicate));
  }

  private static <T> void assertNotContains(String message, List<T> elements, Predicate<T> predicate) {
    assertTrue(message, elements.stream().noneMatch(predicate));
  }

  private static boolean permissionEquals(PermissionAssignment permissionAssignment, Permission authorizeAgent) {
    return authorizeAgent.name().toLowerCase().equals(permissionAssignment.permission.id);
  }

  private String describe(final PermissionAssignments permissionAssignments) {
    return permissionAssignments.myPermissionAssignments
      .stream()
      .map(pa -> pa.permission.id + " - " + (pa.project == null ? "global" : pa.project.id) + " - isGlobalScope:" + pa.isGlobalScope)
      .collect(Collectors.joining(", "));
  }


  private List<String> getSubEntitiesNames(@NotNull final Class aClass) {
    ArrayList<String> result = new ArrayList<>();
    for (Method method : aClass.getMethods()) {
      if (method.isAnnotationPresent(XmlAttribute.class) || method.isAnnotationPresent(XmlElement.class)) result.add("method " + method.getName());
    }
    for (Field field : aClass.getFields()) {
      if (field.isAnnotationPresent(XmlAttribute.class) || field.isAnnotationPresent(XmlElement.class)) result.add("field " + field.getName());
    }
    return result;
  }

  private void enable2FA(@NotNull SUser user) {
    final TwoFactorPasswordManager manager = myFixture.getSingletonService(TwoFactorPasswordManager.class);
    final UUID uuid = manager.addDraftCredentials(user, "ABCDABCDABCDABCD", Collections.emptySet());
    manager.confirmSecretKey(user, uuid, 0);
  }
}
