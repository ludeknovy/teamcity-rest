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

package jetbrains.buildServer.server.graphql.model.mutation.agentPool;

import java.util.List;
import org.jetbrains.annotations.NotNull;

public class BulkAssignProjectWithAgentPoolInput {
  @NotNull
  private List<String> myProjectIds;
  private int myAgentPoolId;
  private boolean myExclusively;

  public void setProjectIds(@NotNull List<String> projectIds) {
    myProjectIds = projectIds;
  }

  public void setAgentPoolId(int agentPoolId) {
    myAgentPoolId = agentPoolId;
  }

  public void setExclusively(boolean exclusively) {myExclusively = exclusively; }

  @NotNull
  public List<String> getProjectIds() {
    return myProjectIds;
  }

  public int getAgentPoolId() {
    return myAgentPoolId;
  }

  public boolean getExclusively() { return myExclusively; }
}
