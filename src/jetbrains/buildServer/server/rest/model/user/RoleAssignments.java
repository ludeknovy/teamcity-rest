/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.groups.UserGroup;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.serverSide.auth.RoleEntry;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;

/**
 * User: Yegor Yarko
 * Date: 12.04.2009
 */
@XmlRootElement(name = "roles")
public class RoleAssignments {
  @XmlElement(name = "role")
  public List<RoleAssignment> roleAssignments;

  public RoleAssignments() {
  }

  public RoleAssignments(Collection<RoleEntry> roleEntries, SUser user, @NotNull final ApiUrlBuilder apiUrlBuilder) {
    roleAssignments = new ArrayList<RoleAssignment>(roleEntries.size());
    for (RoleEntry roleEntry : roleEntries) {
      roleAssignments.add(new RoleAssignment(roleEntry, user, apiUrlBuilder));
    }
  }

  public RoleAssignments(Collection<RoleEntry> roleEntries, UserGroup group, @NotNull final ApiUrlBuilder apiUrlBuilder) {
    roleAssignments = new ArrayList<RoleAssignment>(roleEntries.size());
    for (RoleEntry roleEntry : roleEntries) {
      roleAssignments.add(new RoleAssignment(roleEntry, group, apiUrlBuilder));
    }
  }
}
