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

package jetbrains.buildServer.server.rest.model.user;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import jetbrains.buildServer.server.rest.data.PermissionAssignmentData;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.project.Project;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 * @date 18/09/2017
 * @see PermissionRestriction DTO with the same structure and behaviour, but with another semantics.
 */
@XmlRootElement(name = "permissionAssignment")
@XmlType(name = "permissionAssignment")
@ModelDescription("Represents a relation between the specific permission and a project.")
public class PermissionAssignment {
  @Nullable
  @XmlAttribute
  public Boolean isGlobalScope;

  @XmlElement
  public Permission permission;

  /**
   * If project is specified, the permission is granted for this specific project, nothing can be derived about the subprojects
   * If the project is not specified, for project-level permission it means the permission is granted for all the projects on the server
   */
  @XmlElement(name = "project")
  public Project project;

  public PermissionAssignment() {
  }

  /**
   * Creates global permission
   */
  public PermissionAssignment(
    @NotNull final PermissionAssignmentData permissionAssignment,
    @NotNull final Fields fields,
    @NotNull final BeanContext beanContext
  ) {
    String internalProjectId = permissionAssignment.getInternalProjectId();
    permission = ValueWithDefault.decideDefault(
      fields.isIncluded("permission"),
      () -> new Permission(
        permissionAssignment.getPermission(),
        fields.getNestedField("permission", Fields.LONG, Fields.LONG)
      )
    );

    if (internalProjectId != null) {
      project = ValueWithDefault.decideDefault(
        fields.isIncluded("project", true, true),
        () -> getProject(
          internalProjectId,
          fields.getNestedField("project", Fields.SHORT, Fields.SHORT),
          beanContext
        )
      );
    }

    isGlobalScope = ValueWithDefault.decideDefault(
      fields.isIncluded("isGlobalScope", true, true),
      () -> internalProjectId == null
    );
  }

  @NotNull
  private Project getProject(final @NotNull String internalProjectId, final @NotNull Fields field, final @NotNull BeanContext beanContext) {
    ProjectManager projectManager = beanContext.getSingletonService(ProjectManager.class);
    try {
      SProject projectById = projectManager.findProjectById(internalProjectId);
      if (projectById != null) {
        return new Project(projectById, field, beanContext);
      }
    } catch (Exception e) {
      //ignore
    }
    return new Project(null, internalProjectId, field, beanContext);
  }
}
