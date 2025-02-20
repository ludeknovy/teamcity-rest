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

package jetbrains.buildServer.server.rest.model.federation;


import com.google.common.collect.Iterables;
import java.util.List;
import java.util.function.Function;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.federation.TeamCityServer;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import org.jetbrains.annotations.NotNull;

import static java.util.stream.Collectors.toList;

@SuppressWarnings("PublicField")
@XmlRootElement(name = "servers")
public class FederationServers {
  @XmlAttribute
  public Integer count;

  @XmlElement(name = "server")
  public List<FederationServer> servers;

  public FederationServers() {
  }

  public FederationServers(final List<TeamCityServer> servers, @NotNull final Fields fields) {
    this.servers = ValueWithDefault.decideDefault(fields.isIncluded("server", true), () ->
      servers.stream().map(toFederationServer(fields)).collect(toList())
    );
    this.count = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), Iterables.size(servers));
  }

  @NotNull
  private Function<TeamCityServer, FederationServer> toFederationServer(final @NotNull Fields fields) {
    return server -> new FederationServer(server, fields.getNestedField("server", Fields.SHORT, Fields.LONG));
  }
}
