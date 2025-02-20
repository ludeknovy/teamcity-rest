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

package jetbrains.buildServer.server.graphql.util;

import graphql.ErrorClassification;

public enum TeamCityGraphQLErrorType implements ErrorClassification {
  /** Not enough permissions to perform certain operation */
  ACCESS_DENIED,
  /** Unexpected server error, not a valid result of operation execution */
  SERVER_ERROR,
  /** Expected operation failed error, valid result of operation execution */
  OPERATION_FAILED,
  /** Entity not found */
  NOT_FOUND
}
