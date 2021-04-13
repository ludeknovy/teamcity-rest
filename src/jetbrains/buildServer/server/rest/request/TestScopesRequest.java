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

package jetbrains.buildServer.server.rest.request;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.PagedSearchResult;
import jetbrains.buildServer.server.rest.data.problem.TestOccurrenceFinder;
import jetbrains.buildServer.server.rest.data.problem.scope.TestScope;
import jetbrains.buildServer.server.rest.data.problem.scope.TestScopesCollector;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.model.problem.scope.TestScopes;
import jetbrains.buildServer.server.rest.util.BeanContext;
import org.jetbrains.annotations.NotNull;

@Path(TestScopesRequest.API_SUB_URL)
@Api("Scopes")
public class TestScopesRequest {
  public static final String API_SUB_URL = Constants.API_URL + "/testScopes";
  @Context @NotNull private BeanContext myBeanContext;
  @Context @NotNull private ServiceLocator myServiceLocator;
  @Context @NotNull private ApiUrlBuilder myApiUrlBuilder;
  @Context @NotNull private TestScopesCollector myTestScopesCollector;

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

    String nonPatchedDimension = patchedLocator.getSingleDimensionValue(TestScopesCollector.TEST_OCCURRENCES);
    patchedLocator.setDimension(TestScopesCollector.TEST_OCCURRENCES, TestOccurrenceFinder.patchLocatorForPersonalBuilds(nonPatchedDimension, request));
    patchedLocator.setDimension(TestScopesCollector.SCOPE_TYPE, scopeName);

    PagedSearchResult<TestScope> items = myTestScopesCollector.getPagedItems(patchedLocator);

    PagerData pager = new PagerData(uriInfo.getRequestUriBuilder(), request.getContextPath(), items, locatorText, "locator");

    return new TestScopes(items.myEntries, new Fields(fields), pager, uriInfo, myBeanContext);
  }
}
