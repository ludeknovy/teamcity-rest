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

package jetbrains.buildServer.server.rest.model.agent;

import java.util.List;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelBaseType;
import jetbrains.buildServer.server.rest.swagger.constants.ObjectType;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.CollectionsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@XmlRootElement(name = "compatibilities")
@XmlType(name = "compatibilities")
@ModelBaseType(ObjectType.LIST)
@SuppressWarnings("PublicField")
public class Compatibilities {
  @XmlAttribute
  public Integer count;

  @XmlElement(name = "compatibility")
  public List<Compatibility> compatibilities;

  public Compatibilities() {
  }

  public Compatibilities(@Nullable final List<Compatibility.AgentCompatibilityData> compatibilitiesP,
                         @Nullable final SBuildAgent contextAgent, @Nullable final SBuildType contextBuildType,
                         @NotNull final Fields fields, final @NotNull BeanContext beanContext) {
    compatibilities = compatibilitiesP == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("compatibility", true, true), () -> {
        Fields nestedFields = fields.getNestedField("compatibility");
        return CollectionsUtil.convertCollection(compatibilitiesP, source -> new Compatibility(source, contextAgent, contextBuildType, nestedFields, beanContext));
      });

    count = compatibilitiesP == null ? null : ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), compatibilitiesP.size());
  }
}
