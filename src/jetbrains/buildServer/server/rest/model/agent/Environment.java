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

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelDescription;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.SBuildAgent;
import org.jetbrains.annotations.NotNull;

/**
 * @author Yegor.Yarko
 * Date: 20/07/2017
 */
@XmlRootElement(name = "environment")
@XmlType(name = "environment")
@ModelDescription("Represents the details of the agent's OS.")
@SuppressWarnings("PublicField")
public class Environment {
  @XmlAttribute
  public String osType;
  @XmlAttribute
  public String osName;

  public Environment() {
  }

  public Environment(@NotNull final SBuildAgent agent, @NotNull final Fields fields, final @NotNull BeanContext beanContext) {
    osType = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("osType"), () -> Agent.getAgentOsType(agent));
    osName = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("osName"), agent::getOperatingSystemName);
  }
}
