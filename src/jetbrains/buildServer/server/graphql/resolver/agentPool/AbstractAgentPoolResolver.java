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

package jetbrains.buildServer.server.graphql.resolver.agentPool;

import com.intellij.openapi.util.Pair;
import graphql.schema.DataFetchingEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jetbrains.buildServer.clouds.CloudClientEx;
import jetbrains.buildServer.clouds.CloudImage;
import jetbrains.buildServer.clouds.CloudProfile;
import jetbrains.buildServer.clouds.server.CloudManager;
import jetbrains.buildServer.server.graphql.model.agentPool.AbstractAgentPool;
import jetbrains.buildServer.server.graphql.model.agentPool.AgentPoolPermissions;
import jetbrains.buildServer.server.graphql.model.connections.PaginationArguments;
import jetbrains.buildServer.server.graphql.model.connections.agent.AgentPoolAgentTypesConnection;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolAgentsConnection;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolCloudImagesConnection;
import jetbrains.buildServer.server.graphql.model.connections.agentPool.AgentPoolProjectsConnection;
import jetbrains.buildServer.server.graphql.model.filter.AgentPoolAgentTypesFilter;
import jetbrains.buildServer.server.graphql.model.filter.ProjectsFilter;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.agentPools.AgentPool;
import jetbrains.buildServer.serverSide.agentTypes.AgentTypeFinder;
import jetbrains.buildServer.serverSide.agentTypes.AgentTypeKey;
import jetbrains.buildServer.serverSide.agentTypes.SAgentType;
import jetbrains.buildServer.serverSide.auth.AuthUtil;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.Permission;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
public class AbstractAgentPoolResolver {
  private final ProjectManager myProjectManager;
  private final AgentPoolActionsAccessChecker myPoolActionsAccessChecker;
  private final SecurityContextEx mySecurityContext;
  private final AgentTypeFinder myAgentTypeFinder;
  private final CloudManager myCloudManager;

  public AbstractAgentPoolResolver(@NotNull ProjectManager projectManager,
                                   @NotNull AgentPoolActionsAccessChecker poolActionsAccessChecker,
                                   @NotNull CloudManager cloudManager,
                                   @NotNull AgentTypeFinder agentTypeFinder,
                                   @NotNull final SecurityContextEx securityContext) {
    myProjectManager = projectManager;
    myPoolActionsAccessChecker = poolActionsAccessChecker;
    mySecurityContext = securityContext;
    myCloudManager = cloudManager;
    myAgentTypeFinder = agentTypeFinder;
  }

  @NotNull
  public AgentPoolAgentTypesConnection agentTypes(@NotNull AbstractAgentPool pool, @NotNull AgentPoolAgentTypesFilter filter) {
    Stream<SAgentType> agentTypes = myAgentTypeFinder.getAgentTypesByPool(pool.getRealPool().getAgentPoolId()).stream();
    if(filter.getCloud() != null) {
      boolean isCloudRequired = filter.getCloud();
      agentTypes = agentTypes.filter(at -> at.isCloud() == isCloudRequired);
    }

    return new AgentPoolAgentTypesConnection(agentTypes.collect(Collectors.toList()), PaginationArguments.everything());
  }

  @NotNull
  public AgentPoolAgentsConnection agents(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    List<SBuildAgent> agents = myAgentTypeFinder.getAgentTypesByPool(pool.getRealPool().getAgentPoolId()).stream()
                                                .filter(agentType -> !agentType.isCloud())
                                                .map(SAgentType::getRealAgent)
                                                .collect(Collectors.toList());

    return new AgentPoolAgentsConnection(agents, PaginationArguments.everything());
  }

  @NotNull
  public AgentPoolProjectsConnection projects(@NotNull AbstractAgentPool pool, @NotNull ProjectsFilter filter, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = pool.getRealPool();

    Collection<String> projectIds = realPool.getProjectIds();
    Stream<SProject> projects = myProjectManager.findProjects(projectIds).stream();
    if(filter.getArchived() != null) {
      projects = projects.filter(p -> p.isArchived() == filter.getArchived());
    }
    if(filter.getVirtual() != null) {
      projects = projects.filter(p -> p.isVirtual() == filter.getVirtual());
    }

    Integer excludedProjectsCount = null;
    if(env.getSelectionSet().contains("excludedCount")) {
      AuthorityHolder authHolder = mySecurityContext.getAuthorityHolder();
      excludedProjectsCount = (int) projectIds.stream().filter(projectId -> !authHolder.isPermissionGrantedForProject(projectId, Permission.VIEW_PROJECT)).count();
    }

    return new AgentPoolProjectsConnection(projects.collect(Collectors.toList()), excludedProjectsCount, PaginationArguments.everything());
  }

  @NotNull
  public AgentPoolPermissions permissions(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    jetbrains.buildServer.serverSide.agentPools.AgentPool realPool = pool.getRealPool();
    int poolId = realPool.getAgentPoolId();
    AuthorityHolder authHolder = mySecurityContext.getAuthorityHolder();

    boolean canManagePool = !realPool.isProjectPool() &&
                            realPool.getAgentPoolId() != AgentPool.DEFAULT_POOL_ID &&
                            authHolder.isPermissionGrantedGlobally(Permission.MANAGE_AGENT_POOLS);

    BooleanSupplier canAuthorizeUnauthorizeAgent     = () -> AuthUtil.hasPermissionToAuthorizeAgentsInPool(authHolder, realPool);
    BooleanSupplier canEnableDisableAgent            = () -> AuthUtil.hasPermissionToEnableAgentsInPool(authHolder, realPool);
    BooleanSupplier canManageProjectPoolAssociations = () -> myPoolActionsAccessChecker.canManageProjectsInPool(poolId);
    BooleanSupplier canManageAgents                  = () -> myPoolActionsAccessChecker.canManageAgentsInPool(realPool);

    return new AgentPoolPermissions(canAuthorizeUnauthorizeAgent, canManageProjectPoolAssociations, canEnableDisableAgent, canManageAgents, canManagePool);
  }

  @NotNull
  public AgentPoolCloudImagesConnection cloudImages(@NotNull AbstractAgentPool pool, @NotNull DataFetchingEnvironment env) {
    List<Pair<CloudProfile, CloudImage>> images = new ArrayList<>();

    for(SAgentType agentType : myAgentTypeFinder.getAgentTypesByPool(pool.getRealPool().getAgentPoolId())) {
      if(!agentType.isCloud()) continue;

      AgentTypeKey targetAgentTypeKey = agentType.getAgentTypeKey();
      CloudProfile profile = myCloudManager.findProfileGloballyById(targetAgentTypeKey.getProfileId());
      if(profile == null) continue;

      CloudClientEx client = myCloudManager.getClient(profile.getProjectId(), profile.getProfileId());

      for(CloudImage image : client.getImages()) {
        SAgentType type = myCloudManager.getDescriptionFor(profile, image.getId());
        if(type == null) continue;

        if(targetAgentTypeKey.equals(type.getAgentTypeKey())) {
          images.add(new Pair<>(profile, image));

          break;
        }
      }
    }

    return new AgentPoolCloudImagesConnection(images, PaginationArguments.everything());
  }
}
