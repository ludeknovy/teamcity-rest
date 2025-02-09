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

package jetbrains.buildServer.server.rest.data.problem.scope;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jetbrains.buildServer.BuildProblemTypes;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.problem.Orders;
import jetbrains.buildServer.server.rest.data.problem.ProblemOccurrenceFinder;
import jetbrains.buildServer.server.rest.data.problem.tree.*;
import jetbrains.buildServer.server.rest.jersey.provider.annotated.JerseyContextSingleton;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.impl.problems.BuildProblemImpl;
import jetbrains.buildServer.serverSide.problems.BuildProblem;
import org.apache.commons.lang3.BooleanUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@JerseyContextSingleton
@Component
public class ProblemOccurrencesTreeCollector {
  private static final String UNCATEGORIZED_PROBLEM = "$$uncategorized$$"; // stub for problem type when typeDecription == null

  public static final String ORDER_BY = "orderBy";
  public static final String MAX_CHILDREN = "maxChildren";
  public static final String SUB_TREE_ROOT_ID = "subTreeRootId";

  private static final int DEFAULT_MAX_CHILDREN = 5;
  private static final String DEFAULT_NODE_ORDER_BY_NEW_FAILED_COUNT = "newFailedCount:desc";

  private static final Orders<ScopeTree.Node<BuildProblem, ProblemCounters>> SUPPORTED_ORDERS = new Orders<ScopeTree.Node<BuildProblem, ProblemCounters>>()
    .add("name", Comparator.comparing(node -> node.getScope().getName()))
    .add("count", Comparator.comparing(node -> node.getCounters().getCount()))
    .add("childrenCount", Comparator.comparing(node -> node.getChildren().size()))
    .add("newFailedCount", Comparator.comparing(node -> node.getCounters().getNewFailed()));

  private static final Comparator<BuildProblem> NEW_FAILED_FIRST_THEN_BY_ID = (bp1, bp2) -> {
    if(!(bp1 instanceof BuildProblemImpl) || !(bp2 instanceof BuildProblemImpl)) {
      return Integer.compare(bp1.getId(), bp2.getId());
    }

    boolean firstIsNew = BooleanUtils.isTrue(((BuildProblemImpl) bp1).isNew());
    boolean secondIsNew = BooleanUtils.isTrue(((BuildProblemImpl) bp2).isNew());

    if (firstIsNew && !secondIsNew) {
      return -1;
    }
    if (!firstIsNew && secondIsNew) {
      return 1;
    }

    return Integer.compare(bp1.getId(), bp2.getId());
  };

  private final ProblemOccurrenceFinder myProblemOccurrenceFinder;

  public ProblemOccurrencesTreeCollector(@NotNull ProblemOccurrenceFinder problemOccurrenceFinder) {
    myProblemOccurrenceFinder = problemOccurrenceFinder;
  }

  public List<ScopeTree.Node<BuildProblem, ProblemCounters>> getTree(@NotNull Locator locator) {
    locator.addSupportedDimensions(
      ORDER_BY, SUB_TREE_ROOT_ID,
      ProblemOccurrenceFinder.BUILD, ProblemOccurrenceFinder.AFFECTED_PROJECT, // TODO: if build overview than build dimension will be present
      ProblemOccurrenceFinder.TYPE,
      ProblemOccurrenceFinder.CURRENTLY_INVESTIGATED, ProblemOccurrenceFinder.CURRENTLY_MUTED
    );
    locator.addHiddenDimensions(ProblemOccurrenceFinder.SNAPSHOT_DEPENDENCY_PROBLEM);

    ScopeTree<BuildProblem, ProblemCounters> tree = getTreeByLocator(locator);
    Comparator<ScopeTree.Node<BuildProblem, ProblemCounters>> nodeOrder = getNodeOrder(locator);

    TreeSlicingOptions<BuildProblem, ProblemCounters> slicingOptions = new TreeSlicingOptions<>(getMaxChildrenFunction(locator), NEW_FAILED_FIRST_THEN_BY_ID, nodeOrder);

    if (locator.isAnyPresent(SUB_TREE_ROOT_ID)) {
      String subTreeRootId = locator.getSingleDimensionValue(SUB_TREE_ROOT_ID);
      locator.checkLocatorFullyProcessed();
      //noinspection ConstantConditions
      return tree.getFullNodeAndSlicedOrderedSubtree(subTreeRootId, slicingOptions);
    }

    locator.checkLocatorFullyProcessed();

    return tree.getSlicedOrderedTree(slicingOptions);
  }

  public List<ScopeTree.Node<BuildProblem, ProblemCounters>> getTreeFromBuildPromotions(@NotNull Stream<BuildPromotion> promotionStream, @NotNull Locator treeLocator) {
    treeLocator.addSupportedDimensions(SUB_TREE_ROOT_ID);
    final String problemsLocator = "build:%d,type:(snapshotDependencyProblem:false)";
    Stream<BuildProblem> problemStream = promotionStream
      .filter(promotion -> promotion.getAssociatedBuild() != null)
      .flatMap(promotion -> myProblemOccurrenceFinder.getItems(String.format(problemsLocator, promotion.getAssociatedBuild().getBuildId())).myEntries.stream());

    List<LeafInfo<BuildProblem, ProblemCounters>> problems = groupProblems(problemStream);

    ScopeTree<BuildProblem, ProblemCounters> tree = new ScopeTree<>(
      new ProblemScope(SProject.ROOT_PROJECT_ID, SProject.ROOT_PROJECT_ID, ProblemScopeType.PROJECT),
      new ProblemCounters(0, 0),
      problems
    );

    TreeSlicingOptions<BuildProblem, ProblemCounters> slicingOptions = new TreeSlicingOptions<BuildProblem, ProblemCounters>(
      __ -> DEFAULT_MAX_CHILDREN,
      NEW_FAILED_FIRST_THEN_BY_ID,
      SUPPORTED_ORDERS.getComparator(DEFAULT_NODE_ORDER_BY_NEW_FAILED_COUNT)
    );

    if(treeLocator.isAnyPresent(SUB_TREE_ROOT_ID)) {
      String subTreeRootId = treeLocator.getSingleDimensionValue(SUB_TREE_ROOT_ID);

      treeLocator.checkLocatorFullyProcessed();
      //noinspection ConstantConditions
      return tree.getFullNodeAndSlicedOrderedSubtree(subTreeRootId, slicingOptions);
    }

    treeLocator.checkLocatorFullyProcessed();
    return tree.getSlicedOrderedTree(slicingOptions);
  }

  private ScopeTree<BuildProblem, ProblemCounters> getTreeByLocator(@NotNull Locator fullLocator) {
    String problemsLocator = prepareLocator(fullLocator);
    List<LeafInfo<BuildProblem, ProblemCounters>> problems = groupProblems(myProblemOccurrenceFinder.getItems(problemsLocator).myEntries.stream());

    return new ScopeTree<>(
      new ProblemScope(SProject.ROOT_PROJECT_ID, SProject.ROOT_PROJECT_ID, ProblemScopeType.PROJECT),
      new ProblemCounters(0, 0),
      problems
    );
  }

  private List<LeafInfo<BuildProblem, ProblemCounters>> groupProblems(@NotNull Stream<BuildProblem> problemStream) {
    // buildPromotion id -> problem type description -> List[BuildProblem]
    Map<Long, Map<String, List<BuildProblem>>> problemsByBuildAndType = problemStream
      // filter out unwanted problems early to avoid creating empty tree leaves later.
      .filter(bp -> !BuildProblemTypes.TC_FAILED_TESTS_TYPE.equals(bp.getBuildProblemData().getType()))
      .collect(Collectors.groupingBy(
        buildProblem -> buildProblem.getBuildPromotion().getId(),
        Collectors.groupingBy(
          buildProblem -> Optional.ofNullable(buildProblem.getBuildProblemData().getType()).orElse(UNCATEGORIZED_PROBLEM)
        )
      ));

    return problemsByBuildAndType.values().stream()
                                 .flatMap(problemsByType -> problemsByType.values().stream())
                                 .map(group -> new GroupedProblems(group))
                                 .collect(Collectors.toList());
  }

  private Comparator<ScopeTree.Node<BuildProblem, ProblemCounters>> getNodeOrder(@NotNull Locator locator) {
    if(locator.isAnyPresent(ORDER_BY)) {
      String orderDimension = locator.getSingleDimensionValue(ORDER_BY);
      //noinspection ConstantConditions
      return SUPPORTED_ORDERS.getComparator(orderDimension);
    }

    return SUPPORTED_ORDERS.getComparator(DEFAULT_NODE_ORDER_BY_NEW_FAILED_COUNT);
  }

  private String prepareLocator(@NotNull Locator original) {
    Locator result = Locator.createEmptyLocator();
    result.setDimension(ProblemOccurrenceFinder.AFFECTED_PROJECT, original.getDimensionValue(ProblemOccurrenceFinder.AFFECTED_PROJECT));
    result.setDimension(ProblemOccurrenceFinder.BUILD, original.getDimensionValue(ProblemOccurrenceFinder.BUILD));
    result.setDimension(ProblemOccurrenceFinder.TYPE, original.getDimensionValue(ProblemOccurrenceFinder.TYPE));
    result.setDimension(ProblemOccurrenceFinder.CURRENTLY_INVESTIGATED, original.getDimensionValue(ProblemOccurrenceFinder.CURRENTLY_INVESTIGATED));
    result.setDimension(ProblemOccurrenceFinder.CURRENTLY_MUTED, original.getDimensionValue(ProblemOccurrenceFinder.CURRENTLY_MUTED));
    result.setDimension(PagerData.COUNT, "-1");

    return result.toString();
  }

  public static class GroupedProblems implements LeafInfo<BuildProblem, ProblemCounters> {
    private final Collection<BuildProblem> myProblems;
    private final ProblemCounters myCounters;

    public GroupedProblems(@NotNull Collection<BuildProblem> problems) {
      myProblems = problems;
      myCounters = new ProblemCounters(
        myProblems.size(),
        (int) myProblems.stream().filter(bp -> {
          try {
            return BooleanUtils.isTrue(((BuildProblemImpl) bp).isNew());
          } catch (ClassCastException e) {
            // let's not count as new if we don't know for sure.
            return false;
          }
        }).count()
      );
    }

    @NotNull
    @Override
    public ProblemCounters getCounters() {
      return myCounters;
    }

    @NotNull
    @Override
    public Iterable<Scope> getPath() {
      if(myProblems.size() == 0) {
        // should not actually happen
        return Collections.emptyList();
      }

      BuildProblem firstProblem = myProblems.iterator().next();
      BuildPromotion promotion = firstProblem.getBuildPromotion();
      SBuildType buildType = promotion.getBuildType();

      if(buildType == null) {
        // should not happen either
        return Collections.emptyList();
      }

      List<Scope> scopes = new ArrayList<>();

      for (SProject ancestor : buildType.getProject().getProjectPath()) {
        String ancestorId = ancestor.getExternalId();
        String nodeId = Hashing.sha1().hashString("P" + ancestor.getProjectId(), Charsets.UTF_8).toString();

        scopes.add(new ProblemScope(nodeId, ancestorId, ProblemScopeType.PROJECT));
      }

      String btNodeId = Hashing.sha1().hashString("BT" + buildType.getInternalId(), Charsets.UTF_8).toString();
      scopes.add(new ProblemScope(btNodeId, buildType.getExternalId(), ProblemScopeType.BUILD_TYPE));
      String buildNodeId = Hashing.sha1().hashString("B" + Long.toString(promotion.getId()), Charsets.UTF_8).toString();
      scopes.add(new ProblemScope(buildNodeId, Long.toString(promotion.getId()), ProblemScopeType.BUILD));

      String problemType = firstProblem.getBuildProblemData().getType();
      scopes.add(new ProblemScope(buildType.getExternalId() + Long.toString(promotion.getId()) + problemType, problemType, ProblemScopeType.PROBLEM_TYPE));

      return scopes;
    }

    @NotNull
    @Override
    public Collection<BuildProblem> getData() {
      return myProblems;
    }
  }

  public static class ProblemScope implements Scope {
    private final String myId;
    private final String myName;
    private final ProblemScopeType myType;

    public ProblemScope(@NotNull String id, @NotNull String name, @NotNull ProblemScopeType type) {
      myId = id;
      myName = name;
      myType = type;
    }

    @Override
    @NotNull
    public String getName() {
      return myName;
    }

    @NotNull
    @Override
    public String getId() {
      return myId;
    }

    @NotNull
    public ProblemScopeType getType() {
      return myType;
    }

    @Override
    public boolean isLeaf() {
      return myType == ProblemScopeType.PROBLEM_TYPE;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ProblemScope that = (ProblemScope)o;

      if (!myName.equals(that.myName)) return false;
      return myType == that.myType;
    }

    @Override
    public int hashCode() {
      int result = myName.hashCode();
      result = 31 * result + myType.hashCode();
      return result;
    }
  }

  public enum ProblemScopeType {
    PROJECT,
    BUILD_TYPE,
    BUILD,
    PROBLEM_TYPE
  }

  public static class ProblemCounters implements TreeCounters<ProblemCounters> {
    private final int myCount;
    private final int myNewFailed;

    public ProblemCounters(int count, int newFailed) {
      myCount = count;
      myNewFailed = newFailed;
    }

    public int getCount() {
      return myCount;
    }

    public int getNewFailed() {
      return myNewFailed;
    }

    @Override
    public ProblemCounters combinedWith(@NotNull ProblemCounters other) {
      return new ProblemCounters(myCount + other.getCount(), myNewFailed + other.getNewFailed());
    }
  }

  @NotNull
  private static Function<ScopeTree.Node<BuildProblem, ProblemCounters>, Integer> getMaxChildrenFunction(@NotNull Locator locator) {
    Locator maxChildrenDimension = locator.get(MAX_CHILDREN);
    if (maxChildrenDimension == null) {
      return __ -> DEFAULT_MAX_CHILDREN;
    }

    if (maxChildrenDimension.isSingleValue()) {
      //noinspection DataFlowIssue // already checked with IsSingleValue above
      return __ -> Math.toIntExact(maxChildrenDimension.getSingleValueAsLong());
    }

    int maxChildrenDefault = Math.toIntExact(maxChildrenDimension.getSingleDimensionValueAsLong("default", (long)DEFAULT_MAX_CHILDREN));
    int maxChildrenBuildType = Math.toIntExact(maxChildrenDimension.getSingleDimensionValueAsLong("buildType", (long)maxChildrenDefault));
    int maxChildrenProject = Math.toIntExact(maxChildrenDimension.getSingleDimensionValueAsLong("project", (long)maxChildrenDefault));
    return (node) -> {
      if (node.getScope() instanceof ProblemScope) {
        ProblemScopeType type = ((ProblemScope)node.getScope()).getType();
        if (type == ProblemScopeType.PROJECT) {
          return maxChildrenProject;
        }
        if (type == ProblemScopeType.BUILD_TYPE) {
          return maxChildrenBuildType;
        }
      }
      return maxChildrenDefault;
    };
  }

}
