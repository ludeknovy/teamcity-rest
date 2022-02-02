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

package jetbrains.buildServer.server.rest.data.util;

import jetbrains.buildServer.server.rest.data.FinderDataBinding;
import jetbrains.buildServer.util.ItemProcessor;
import org.jetbrains.annotations.NotNull;

/**
 * Performs item deduplication on the fly, passing only unique items to the processor.
 * Works only for P with due hash/equals.
 */
public class DeduplicatingItemHolder<P> implements FinderDataBinding.ItemHolder<P> {
  @NotNull
  private final FinderDataBinding.ItemHolder<P> myItemHolder;
  @NotNull
  private final DuplicateChecker<P> myDuplicateChecker;

  public DeduplicatingItemHolder(@NotNull final FinderDataBinding.ItemHolder<P> itemHolder, @NotNull final DuplicateChecker<P> duplicateChecker) {
    myItemHolder = itemHolder;
    myDuplicateChecker = duplicateChecker;
  }

  public void process(@NotNull final ItemProcessor<P> processor) {
    myItemHolder.process(item -> {
      if (myDuplicateChecker.checkDuplicateAndRemember(item)) {
        // In a case when given item was already seen signal to continue without passing the item to the real processor.
        return true;
      }

      return processor.processItem(item);
    });
  }
}
