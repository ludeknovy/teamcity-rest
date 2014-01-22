/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

package jetbrains.buildServer.server.rest.model.group;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.groups.SUserGroup;
import jetbrains.buildServer.groups.UserGroup;
import jetbrains.buildServer.groups.UserGroupManager;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.errors.NotFoundException;
import org.jetbrains.annotations.NotNull;

/**
 * @author Yegor.Yarko
 *         Date: 16.04.2009
 */
@XmlRootElement(name = "group-ref")
@XmlType(name = "group-ref")
public class GroupRef {
  @XmlAttribute
  public String key;
  @XmlAttribute
  public String name;
  @XmlAttribute
  public String href;

  public GroupRef() {
  }

  public GroupRef(UserGroup userGroup, @NotNull final ApiUrlBuilder apiUrlBuilder) {
    this.key = userGroup.getKey();
    this.name = userGroup.getName();
    this.href = apiUrlBuilder.getHref(userGroup);
  }

  @NotNull
  public SUserGroup getFromPosted(final ServiceLocator serviceLocator) {
    if (key == null){
      throw new BadRequestException("No 'key' attribute is supplied for the posted group.");
    }
    final SUserGroup userGroupByKey = serviceLocator.getSingletonService(UserGroupManager.class).findUserGroupByKey(key);
    if (userGroupByKey == null)
      throw new NotFoundException("No group is found by key '" + key + "'");
    return userGroupByKey;
  }
}
