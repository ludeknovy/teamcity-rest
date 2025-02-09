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

package jetbrains.buildServer.server.graphql.model.connections.agentPool;


import com.intellij.openapi.util.Pair;
import graphql.execution.DataFetcherResult;
import java.util.Collections;
import java.util.List;
import jetbrains.buildServer.clouds.CloudImage;
import jetbrains.buildServer.clouds.CloudProfile;
import jetbrains.buildServer.server.graphql.model.connections.ExtensibleConnection;
import jetbrains.buildServer.server.graphql.model.connections.LazyEdge;
import jetbrains.buildServer.server.graphql.model.connections.PaginatingConnection;
import jetbrains.buildServer.server.graphql.model.connections.PaginationArguments;
import org.jetbrains.annotations.NotNull;

public class AgentPoolCloudImagesConnection implements ExtensibleConnection<jetbrains.buildServer.server.graphql.model.CloudImage, AgentPoolCloudImagesConnection.AgentPoolCloudImagesConnectionEdge> {
  public static AgentPoolCloudImagesConnection empty() {
    return new AgentPoolCloudImagesConnection(Collections.emptyList(), PaginationArguments.everything());
  }

  @NotNull
  private final PaginatingConnection<Pair<CloudProfile, CloudImage>, jetbrains.buildServer.server.graphql.model.CloudImage, AgentPoolCloudImagesConnectionEdge> myDelegate;

  public AgentPoolCloudImagesConnection(@NotNull List<Pair<CloudProfile, CloudImage>> data, @NotNull PaginationArguments paginationArguments) {
    myDelegate = new PaginatingConnection<>(data, AgentPoolCloudImagesConnectionEdge::new, paginationArguments);
  }

  @NotNull
  @Override
  public DataFetcherResult<List<AgentPoolCloudImagesConnectionEdge>> getEdges() {
    return myDelegate.getEdges();
  }

  public static class AgentPoolCloudImagesConnectionEdge extends LazyEdge<Pair<CloudProfile, CloudImage>, jetbrains.buildServer.server.graphql.model.CloudImage> {
    public AgentPoolCloudImagesConnectionEdge(@NotNull Pair<CloudProfile, CloudImage> data) {
      super(
        data,
        pair -> new jetbrains.buildServer.server.graphql.model.CloudImage(pair.getSecond(), pair.getFirst()),
        pair -> pair.getSecond() // get CloudImage to be inserted into local context
      );
    }
  }
}
