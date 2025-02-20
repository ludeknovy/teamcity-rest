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

package jetbrains.buildServer.server.rest.util;

import java.util.HashMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

public class SimpleStringPool implements StringPool {
  private final HashMap<String, String> myStringPool = new HashMap<>();

  @Contract("null -> null, !null -> !null")
  public String reuse(@Nullable String value) {
    if (value == null) {
      return null;
    }

    String result = myStringPool.get(value);
    if (result == null) {
      myStringPool.put(value, value);

      return value;
    }

    return result;
  }
}
