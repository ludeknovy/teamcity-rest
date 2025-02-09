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

package jetbrains.buildServer.server.rest.request;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.PagedSearchResult;
import jetbrains.buildServer.server.rest.data.problem.TestCountersData;
import jetbrains.buildServer.server.rest.data.problem.scope.TestScope;
import jetbrains.buildServer.server.rest.data.problem.scope.TestScopeTreeCollector;
import jetbrains.buildServer.server.rest.data.problem.scope.TestScopesCollector;
import jetbrains.buildServer.server.rest.data.problem.tree.ScopeTree;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.model.PagerDataImpl;
import jetbrains.buildServer.server.rest.model.problem.scope.TestScopes;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.serverSide.STestRun;
import org.jetbrains.annotations.NotNull;

@Path(TestScopesRequest.API_SUB_URL)
@Api(value = "TestScopes", hidden = true)
public class TestScopesRequest {
  public static final String API_SUB_URL = Constants.API_URL + "/testScopes";
  @Context @NotNull private BeanContext myBeanContext;
  @Context @NotNull private ServiceLocator myServiceLocator;
  @Context @NotNull private ApiUrlBuilder myApiUrlBuilder;
  @Context @NotNull private TestScopesCollector myTestScopesCollector;
  @Context @NotNull private TestScopeTreeCollector myTestScopeTreeCollector;

  // Very highly experimental
  @GET
  @Path("/{scopeName}")
  @Produces({"application/xml", "application/json"})
  @ApiOperation(hidden = true, value = "highly experimental")
  public TestScopes serveGroupedTestOccurrences(@QueryParam("locator") String locatorText,
                                                @PathParam("scopeName") String scopeName,
                                                @QueryParam("fields") String fields,
                                                @Context UriInfo uriInfo,
                                                @Context HttpServletRequest request) {
    Set<String> supportedGroupings = new HashSet<>(Arrays.asList("package", "suite", "class"));
    if (!supportedGroupings.contains(scopeName)) {
      throw new BadRequestException("Invalid scope. Only scopes " + String.join(",", supportedGroupings) + " are supported.");
    }

    Locator patchedLocator = new Locator(locatorText);

    patchedLocator.setDimension(TestScopesCollector.SCOPE_TYPE, scopeName);

    PagedSearchResult<TestScope> items = myTestScopesCollector.getPagedItems(patchedLocator);

    PagerData pager = new PagerDataImpl(uriInfo.getRequestUriBuilder(), request.getContextPath(), items, locatorText, "locator");

    return new TestScopes(items.myEntries, new Fields(fields), pager, uriInfo, myBeanContext);
  }

  // Very highly experimental
  @GET
  @Path("/tree")
  @Produces({"application/xml", "application/json"})
  @ApiOperation(hidden = true, value = "highly experimental")
  public jetbrains.buildServer.server.rest.model.problem.scope.TestScopeTree serveScopesTree(@QueryParam("locator") String locatorText,
                                    @QueryParam("fields") String fields) {
    List<ScopeTree.Node<STestRun, TestCountersData>> treeNodes = myTestScopeTreeCollector.getSlicedTree(Locator.locator(locatorText));

    return new jetbrains.buildServer.server.rest.model.problem.scope.TestScopeTree(treeNodes, new Fields(fields), myBeanContext);
  }

  // Very highly experimental
  @GET
  @Path("/treeTopSlice")
  @Produces({"application/xml", "application/json"})
  @ApiOperation(hidden = true, value = "highly experimental")
  /**
   * Get horizontally sliced tree containing projects and build configurations which are relevant to test runs satisfying given locator
   */
  public jetbrains.buildServer.server.rest.model.problem.scope.TestScopeTree serveScopesTreeTopSlice(@QueryParam("locator") String locatorText,
                                                                                                     @QueryParam("fields") String fields) {
    List<ScopeTree.Node<STestRun, TestCountersData>> treeNodes = myTestScopeTreeCollector.getTopSlicedTree(Locator.locator(locatorText));

    return new jetbrains.buildServer.server.rest.model.problem.scope.TestScopeTree(treeNodes, new Fields(fields), myBeanContext);
  }

  void initForTests(@NotNull BeanContext beanContext, @NotNull TestScopesCollector testScopesCollector, @NotNull TestScopeTreeCollector testScopeTreeCollector) {
    myBeanContext = beanContext;
    myTestScopesCollector = testScopesCollector;
    myTestScopeTreeCollector = testScopeTreeCollector;
  }
}
