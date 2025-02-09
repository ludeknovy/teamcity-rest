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

package jetbrains.buildServer.server.rest.data.parameters;

import java.util.Collection;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.UserParametersHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 *         Date: 07/07/2016
 */
public abstract class UserParametersHolderEntityWithParameters implements EntityWithModifiableParameters {
  @NotNull private final UserParametersHolder myEntity;

  public UserParametersHolderEntityWithParameters(@NotNull final UserParametersHolder entity) {
    myEntity = entity;
  }

  public void addParameter(@NotNull final Parameter param) {
    myEntity.addParameter(param);
  }

  public void removeParameter(@NotNull final String paramName) {
    myEntity.removeParameter(paramName);
  }

  @NotNull
  public Collection<Parameter> getParametersCollection(@Nullable final Locator locator) {
    return myEntity.getParametersCollection();
  }

  @Nullable
  @Override
  public Parameter getParameter(@NotNull final String paramName) {
    return myEntity.getParameter(paramName);
  }

  @Nullable
  public Collection<Parameter> getOwnParametersCollection() {
    return null;
  }
}
