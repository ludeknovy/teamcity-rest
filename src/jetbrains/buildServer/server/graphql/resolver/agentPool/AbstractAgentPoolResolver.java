/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package jetbrains.buildServer.server.graphql.resolver.agentPool;

import com.intellij.openapi.util.Pair;
import graphql.schema.DataFetchingEnvironment;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jetbrains.buildServer.clouds.CloudImage;
import jetbrains.buildServer.clouds.server.CloudManager;
import jetbrains.buildServer.server.graphql.model.agentPool.AbstractAgentPool;
import jetbrains.buildServer.server.graphql.model.agentPool.AgentPoolPermissions;
import jetbrains.buildServer.server.graphql.model.connections.PaginationArguments;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolAgentsConnection;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolCloudImagesConnection;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolProjectsConnection;
import jetbrains.buildServer.server.graphql.model.filter.ProjectsFilter;
import jetbrains.buildServer.server.rest.data.CloudUtil;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.agentPools.AgentPoolManager;
import jetbrains.buildServer.serverSide.auth.AuthUtil;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
public class AbstractAgentPoolResolver {
  private final ProjectManager myProjectManager;
  private final AgentPoolActionsAccessChecker myPoolActionsAccessChecker;
  private final SecurityContextEx mySecurityContext;
  private final BuildAgentManager myBuildAgentManager;
  private final AgentPoolManager myAgentPoolManager;
  private final CloudManager myCloudManager;
  private final CloudUtil myCloudUtil;

  public AbstractAgentPoolResolver(@NotNull ProjectManager projectManager,
                                   @NotNull AgentPoolActionsAccessChecker poolActionsAccessChecker,
                                   @NotNull BuildAgentManager buildAgentManager,
                                   @NotNull AgentPoolManager agentPoolManager,
                                   @NotNull CloudManager cloudManager,
                                   @NotNull CloudUtil cloudUtil,
                                   @NotNull final SecurityContextEx securityContext) {
    myProjectManager = projectManager;
    myPoolActionsAccessChecker = poolActionsAccessChecker;
    mySecurityContext = securityContext;
    myBuildAgentManager = buildAgentManager;
    myAgentPoolManager = agentPoolManager;
    myCloudManager = cloudManager;
    myCloudUtil = cloudUtil;
  }

  @NotNull
  public AgentPoolAgentsConnection agents(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = getRealPoolSafe(pool, env);

    List<SBuildAgent> agents = realPool.getAgentTypeIds().stream()
                                       .map(agentId -> (SBuildAgent) myBuildAgentManager.findAgentById(agentId, false))
                                       .filter(Objects::nonNull)
                                       .collect(Collectors.toList());

    return new AgentPoolAgentsConnection(agents, PaginationArguments.everything());
  }

  @NotNull
  public AgentPoolProjectsConnection projects(@NotNull AbstractAgentPool pool, @NotNull ProjectsFilter filter, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = getRealPoolSafe(pool, env);

    Collection<String> projectIds = realPool.getProjectIds();
    Stream<SProject> projects = myProjectManager.findProjects(projectIds).stream();
    if(filter.getArchived() != null) {
      projects = projects.filter(p -> p.isArchived() == filter.getArchived());
    }

    return new AgentPoolProjectsConnection(projects.collect(Collectors.toList()), PaginationArguments.everything());
  }

  @NotNull
  public AgentPoolPermissions permissions(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = getRealPoolSafe(pool, env);
    int poolId = realPool.getAgentPoolId();
    AuthorityHolder authHolder = mySecurityContext.getAuthorityHolder();

    boolean canAuthorizeUnauthorizeAgent = AuthUtil.hasPermissionToAuthorizeAgentsInPool(authHolder, realPool);
    boolean canEnableDisableAgent = AuthUtil.hasPermissionToEnableAgentsInPool(authHolder, realPool);
    boolean canManageProjectPoolAssociations = myPoolActionsAccessChecker.canManageProjectsInPool(poolId);
    boolean canRemoveAgents = myPoolActionsAccessChecker.canManageAgentsInPool(poolId);

    return new AgentPoolPermissions(canAuthorizeUnauthorizeAgent, canManageProjectPoolAssociations, canEnableDisableAgent, canRemoveAgents);
  }

  @NotNull
  public AgentPoolCloudImagesConnection cloudImages(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    // List[profileId, image]
    List<Pair<String, CloudImage>> images = myCloudManager.listAllProfiles().stream()
                                            .flatMap(profile -> myCloudUtil.getImages(profile).stream().map(img -> new Pair<>(profile.getProfileId(), (CloudImage) img)))
                                            .filter(pair -> Objects.equals(pool.getId(), pair.getSecond().getAgentPoolId()))
                                            .collect(Collectors.toList());

    return new AgentPoolCloudImagesConnection(images, PaginationArguments.everything());
  }

  public jetbrains.buildServer.serverSide.agentPools.AgentPool getRealPoolSafe(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = env.getLocalContext();

    if(realPool != null) {
      return realPool;
    }

    return myAgentPoolManager.findAgentPoolById(pool.getId());
  }
}
