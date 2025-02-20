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
import jetbrains.buildServer.server.rest.data.finder.impl.HealthItemFinder;
import jetbrains.buildServer.server.rest.data.PagedSearchResult;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.model.PagerDataImpl;
import jetbrains.buildServer.server.rest.model.health.HealthCategories;
import jetbrains.buildServer.server.rest.model.health.HealthCategory;
import jetbrains.buildServer.server.rest.model.health.HealthItem;
import jetbrains.buildServer.server.rest.model.health.HealthItems;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.serverSide.healthStatus.ItemCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

@Path(HealthRequest.API_SUB_URL)
@Api(value = "health")
public class HealthRequest {
  @NotNull
  public static final String API_SUB_URL = Constants.API_URL + "/health";
  @NotNull
  @Context
  HealthItemFinder myHealthItemFinder;
  @NotNull
  @Context
  private BeanContext myBeanContext;

  @GET
  @Path("/category/{locator}")
  public HealthCategory getSingleCategory(@PathParam("locator") @Nullable final String locator,
                                          @QueryParam("fields") @Nullable final String fields) {
    return new HealthCategory(myHealthItemFinder.getCategory(locator), new Fields(fields));
  }

  @GET
  @Path("/category")
  @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
  public HealthCategories getCategories(@QueryParam("locator") @Nullable final String locator,
                                        @QueryParam("fields") @Nullable final String fields,
                                        @Context @NotNull final UriInfo uriInfo,
                                        @Context @NotNull final HttpServletRequest request) {
    final PagedSearchResult<ItemCategory> pagedItems = myHealthItemFinder.getCategories(locator);
    final PagerData pagerData = new PagerDataImpl(uriInfo.getRequestUriBuilder(), request.getContextPath(), pagedItems, locator, "locator");
    return new HealthCategories(pagedItems.myEntries, pagerData, new Fields(fields), myBeanContext);
  }

  @GET
  @Path("/{locator}")
  @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
  public HealthItem getSingleHealthItem(@PathParam("locator") @Nullable final String locator,
                                        @QueryParam("fields") @Nullable final String fields) {
    return new HealthItem(myHealthItemFinder.getItem(locator), new Fields(fields));
  }

  @GET
  @Path("/")
  @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
  public HealthItems getHealthItems(@QueryParam("locator") @Nullable final String locator,
                                    @QueryParam("fields") @Nullable final String fields,
                                    @Context @NotNull final UriInfo uriInfo,
                                    @Context @NotNull final HttpServletRequest request) {
    final PagedSearchResult<jetbrains.buildServer.serverSide.healthStatus.HealthStatusItem> pagedItems = myHealthItemFinder.getItems(locator);
    final PagerData pagerData = new PagerDataImpl(uriInfo.getRequestUriBuilder(), request.getContextPath(), pagedItems, locator, "locator");
    return new HealthItems(pagedItems.myEntries, pagerData, new Fields(fields), myBeanContext);
  }

  public void initForTests(@NotNull final BeanContext ctx) {
    myBeanContext = ctx;
    myHealthItemFinder = ctx.getSingletonService(HealthItemFinder.class);
  }
}
