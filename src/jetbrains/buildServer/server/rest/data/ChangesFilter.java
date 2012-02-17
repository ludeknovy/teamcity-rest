/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

package jetbrains.buildServer.server.rest.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.BuildChainChangesCollector;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.Converter;
import jetbrains.buildServer.vcs.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 *         Date: 09.09.2009
 */
public class ChangesFilter extends AbstractFilter<SVcsModification> {
  public static final String IGNORE_CHANGES_FROM_DEPENDENCIES_OPTION = "rest.ignoreChangesFromDependenciesOption";
  @Nullable private SProject myProject;
  @Nullable private SBuildType myBuildType;
  @Nullable private SBuild myBuild;
  @Nullable private VcsRootInstance myVcsRoot;
  @Nullable private SVcsModification mySinceChange;

  public ChangesFilter(@Nullable final SProject project,
                       @Nullable final SBuildType buildType,
                       @Nullable final SBuild build,
                       @Nullable final VcsRootInstance vcsRoot,
                       @Nullable final SVcsModification sinceChange,
                       @Nullable final Long start,
                       @Nullable final Integer count) {
    super(start, count);
    myProject = project;
    myBuildType = buildType;
    myBuild = build;
    myVcsRoot = vcsRoot;
    mySinceChange = sinceChange;
  }

  @Override
  protected boolean isIncluded(@NotNull final SVcsModification change) {
    if (myVcsRoot != null) {
      if (change.isPersonal()) {
        return false;
      } else {
        assert myVcsRoot != null;
        if (myVcsRoot.getId() != change.getVcsRoot().getId()) {
          return false;
        }
      }
    }

    if (mySinceChange != null && mySinceChange.getId() >= change.getId()) {
      return false;
    }

    // include by myBuild should be already handled by this time on the upper level

    return true;
  }

  //todo: BuiltType is ignored if VCS root is specified; sometimes we return filtered changes by checkout rules and sometimes not
  //todo: sometimes with panding sometimes not?
  public List<SVcsModification> getMatchingChanges(@NotNull final VcsModificationHistory vcsHistory,
                                                   final BuildChainChangesCollector buildChainChangesCollector) {


    final FilterItemProcessor<SVcsModification> filterItemProcessor = new FilterItemProcessor<SVcsModification>(this);
    if (myBuild != null) {
      processList(getBuildChanges(myBuild), filterItemProcessor);
    } else if (myBuildType != null) {
      processList(getBuildTypeChanges(vcsHistory, myBuildType, buildChainChangesCollector), filterItemProcessor);
    } else if (myVcsRoot != null) {
      if (mySinceChange != null) {
        processList(vcsHistory.getModificationsInRange(myVcsRoot, mySinceChange.getId(), null), filterItemProcessor);
      } else {
        //todo: highly inefficient!
        processList(vcsHistory.getAllModifications(myVcsRoot), filterItemProcessor);
      }
    } else if (myProject != null) {
      processList(getProjectChanges(vcsHistory, myProject, mySinceChange), filterItemProcessor);
    } else {
      //todo: highly inefficient!
      processList(vcsHistory.getAllModifications(), filterItemProcessor);
    }

    return filterItemProcessor.getResult();
  }

  private List<SVcsModification> getBuildTypeChanges(final VcsModificationHistory vcsHistory,
                                                     final SBuildType buildType,
                                                     final BuildChainChangesCollector buildChainChangesCollector) {
    if (TeamCityProperties.getBoolean(IGNORE_CHANGES_FROM_DEPENDENCIES_OPTION) || !buildType.getOption(BuildTypeOptions.BT_SHOW_DEPS_CHANGES)){
      return vcsHistory.getAllModifications(buildType);
    }
    final Map<SBuild,List<SDependencyModification>> chainModifications = buildChainChangesCollector.getChainModifications(buildType);

    final ArrayList<SVcsModification> result = new ArrayList<SVcsModification>();
    for (Map.Entry<SBuild, List<SDependencyModification>> sBuildListEntry : chainModifications.entrySet()) {
      result.addAll(CollectionsUtil.convertCollection(sBuildListEntry.getValue(), new Converter<SVcsModification, SDependencyModification>() {
        public SVcsModification createFrom(@NotNull final SDependencyModification source) {
          return source.asVcsModification();
        }
      }));
    }
    return result;
  }

  private static List<SVcsModification> getBuildChanges(final SBuild build) {
    if (TeamCityProperties.getBoolean(IGNORE_CHANGES_FROM_DEPENDENCIES_OPTION) || !build.getBuildType().getOption(BuildTypeOptions.BT_SHOW_DEPS_CHANGES)) {
      return build.getContainingChanges();
    }
    final List<SDependencyModification> chainChanges =
      ((BuildPromotionEx)build).getChainChanges(SelectPrevBuildPolicy.SINCE_FIRST_BUILD, false);
    return CollectionsUtil.convertCollection(chainChanges, new Converter<SVcsModification, SDependencyModification>() {
      public SVcsModification createFrom(@NotNull final SDependencyModification source) {
        return source.asVcsModification();
      }
    });
  }

  static private List<SVcsModification> getProjectChanges(@NotNull final VcsModificationHistory vcsHistory,
                                                          @NotNull final SProject project,
                                                          @Nullable final SVcsModification sinceChange) {
    final List<VcsRootInstance> vcsRoots = project.getVcsRootInstances();
    final List<SVcsModification> result = new ArrayList<SVcsModification>();
    for (VcsRootInstance root : vcsRoots) {
      if (sinceChange != null) {
        result.addAll(vcsHistory.getModificationsInRange(root, sinceChange.getId(), null));
      } else {
        //todo: highly inefficient!
        result.addAll(vcsHistory.getAllModifications(root));
      }
    }
    Collections.sort(result);
    return result;
  }
}
