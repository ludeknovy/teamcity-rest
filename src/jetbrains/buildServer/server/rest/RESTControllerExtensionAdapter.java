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

package jetbrains.buildServer.server.rest;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * @see jetbrains.buildServer.server.rest.RESTControllerExtension
 * @author Yegor.Yarko
 *         Date: 03.08.2010
 */
public abstract class RESTControllerExtensionAdapter implements RESTControllerExtension{
  @NotNull
  private ConfigurableApplicationContext myContext;

  @NotNull
  public abstract String getPackage();

  @NotNull
  public ConfigurableApplicationContext getContext() {
    return myContext;
  }

  /**
   * Do not invoke!
   * It's autowired by Spring.
   */
  @Autowired
  public void setContext(@NotNull ConfigurableApplicationContext myContext) {
    this.myContext = myContext;
  }
}
