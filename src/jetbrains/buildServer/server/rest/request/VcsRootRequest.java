/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.server.rest.ApiUrlBuilder;
import jetbrains.buildServer.server.rest.data.DataProvider;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.Properties;
import jetbrains.buildServer.server.rest.model.buildType.BuildTypeUtil;
import jetbrains.buildServer.server.rest.model.buildType.VcsRootInstances;
import jetbrains.buildServer.server.rest.model.buildType.VcsRoots;
import jetbrains.buildServer.server.rest.model.change.VcsRoot;
import jetbrains.buildServer.server.rest.model.change.VcsRootInstance;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsManager;
import jetbrains.buildServer.vcs.VcsRootScope;
import org.jetbrains.annotations.NotNull;

/* todo: investigate logging issues:
    - disable initialization lines into stdout
    - too long number passed as finish for builds produces 404
*/

@Path(VcsRootRequest.API_VCS_ROOTS_URL)
public class VcsRootRequest {
  @Context
  private DataProvider myDataProvider;
  @Context
  private ApiUrlBuilder myApiUrlBuilder;
  @Context
  private ServiceLocator myServiceLocator;

  public static final String API_VCS_ROOTS_URL = Constants.API_URL + "/vcs-roots";

  public static String getVcsRootHref(final jetbrains.buildServer.vcs.VcsRoot root) {
    return API_VCS_ROOTS_URL + "/id:" + root.getId();
  }

  public static String getVcsRootInstanceHref(final jetbrains.buildServer.vcs.VcsRootInstance vcsRootInstance) {
    return API_VCS_ROOTS_URL + "/id:" + vcsRootInstance.getParentId() + "/instances/id:" + vcsRootInstance.getId();
  }

  @GET
  @Produces({"application/xml", "application/json"})
  public VcsRoots serveRoots() {
    return new VcsRoots(myDataProvider.getAllVcsRoots(), myApiUrlBuilder);
  }

  @POST
  @Consumes({"application/xml", "application/json"})
  @Produces({"application/xml", "application/json"})
  public VcsRoot addRoot(VcsRoot vcsRootDescription) {
    checkVcsRootDescription(vcsRootDescription);
    final SVcsRoot newVcsRoot = myDataProvider.getVcsManager()
      .createNewVcsRoot(vcsRootDescription.vcsName, vcsRootDescription.name != null ? vcsRootDescription.name : null,
                        vcsRootDescription.properties.getMap(), createScope(vcsRootDescription));
    if (vcsRootDescription.modificationCheckInterval != null){
      newVcsRoot.setModificationCheckInterval(vcsRootDescription.modificationCheckInterval);
    }
    myDataProvider.getVcsManager().persistVcsRoots();
    return new VcsRoot(newVcsRoot, myDataProvider, myApiUrlBuilder);
  }

  private void checkVcsRootDescription(final VcsRoot description) {
    //might need to check for validity: not specified id, status, lastChecked attributes, etc.
    if (StringUtil.isEmpty(description.vcsName)) {
      //todo: include list of available supports here
      throw new BadRequestException("Attribute 'vcsName' must be specified when creating VCS root. Should be a valid VCS support name.");
    }
    if (description.properties == null) {
      throw new BadRequestException("Element 'properties' must be specified when creating VCS root.");
    }
  }

  @NotNull
  private VcsRootScope createScope(final VcsRoot vcsRootDescription) {
    if (vcsRootDescription.shared != null && vcsRootDescription.shared){
      if (vcsRootDescription.project != null){
        throw new BadRequestException("Project should not be specified if the VCS root is shared.");
      }
      return VcsRootScope.globalScope();
    }else{
      return VcsRootScope.projectScope(myDataProvider.getProject(getProjectLocator(vcsRootDescription)));
    }
  }

  // see also BuildTypeUtil.getVcsRoot
  private String getProjectLocator(final VcsRoot description) {
    if (!StringUtil.isEmpty(description.projectLocator)){
      if (description.project != null){
        throw new BadRequestException("Only one from projectLocator attribute and project element should be specified.");
      }
      return description.projectLocator;
    }else{
      if (description.project == null){
        throw new BadRequestException("Either projectLocator attribute or project element should be specified.");
      }
      final String projectHref = description.project.href;
      if (StringUtil.isEmpty(projectHref)){
        throw new BadRequestException("project element should have valid href attribute.");
      }
      return BuildTypeUtil.getLastPathPart(projectHref);
    }  }


  @GET
  @Path("/{vcsRootLocator}")
  @Produces({"application/xml", "application/json"})
  public VcsRoot serveRoot(@PathParam("vcsRootLocator") String vcsRootLocator) {
    return new VcsRoot(myDataProvider.getVcsRoot(vcsRootLocator), myDataProvider, myApiUrlBuilder);
  }

  @DELETE
  @Path("/{vcsRootLocator}")
  @Produces({"application/xml", "application/json"})
  public void deleteRoot(@PathParam("vcsRootLocator") String vcsRootLocator) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    myDataProvider.getVcsManager().removeVcsRoot(vcsRoot.getId());
    myDataProvider.getVcsManager().persistVcsRoots();
  }

  @GET
  @Path("/{vcsRootLocator}/instances")
  @Produces({"application/xml", "application/json"})
  public VcsRootInstances serveRootInstances(@PathParam("vcsRootLocator") String vcsRootLocator) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    final VcsManager vcsManager = myDataProvider.getVcsManager();
    //todo: (TeamCity) open API is there a better way to do this?
    final List<SBuildType> allConfigurationUsages = vcsManager.getAllConfigurationUsages(vcsRoot);
    final HashSet<jetbrains.buildServer.vcs.VcsRootInstance> result = new HashSet<jetbrains.buildServer.vcs.VcsRootInstance>();
    for (SBuildType buildType : allConfigurationUsages) {
      final jetbrains.buildServer.vcs.VcsRootInstance rootInstance = buildType.getVcsRootInstanceForParent(vcsRoot);
      if (rootInstance!=null){
        result.add(rootInstance);
      }
    }
    return new VcsRootInstances(result, myApiUrlBuilder);
  }

  @GET
  @Path("/{vcsRootLocator}/instances/{vcsRootInstanceLocator}")
  @Produces({"application/xml", "application/json"})
  public VcsRootInstance serveRootInstance(@PathParam("vcsRootLocator") String vcsRootLocator,
                                           @PathParam("vcsRootInstanceLocator") String vcsRootInstanceLocator) {
    return new VcsRootInstance(myDataProvider.getVcsRootInstance(vcsRootInstanceLocator), myDataProvider, myApiUrlBuilder);
  }

  @GET
  @Path("/{vcsRootLocator}/instances/{vcsRootInstanceLocator}/properties")
  @Produces({"application/xml", "application/json"})
  public Properties serveRootInstanceProperties(@PathParam("vcsRootLocator") String vcsRootLocator,
                                           @PathParam("vcsRootInstanceLocator") String vcsRootInstanceLocator) {
    return new Properties(myDataProvider.getVcsRootInstance(vcsRootInstanceLocator).getProperties());
  }


  @GET
  @Path("/{vcsRootLocator}/instances/{vcsRootInstanceLocator}/{field}")
  @Produces("text/plain")
  public String serveInstanceField(@PathParam("vcsRootLocator") String vcsRootLocator,
                                   @PathParam("vcsRootInstanceLocator") String vcsRootInstanceLocator,
                                   @PathParam("field") String fieldName) {
    final jetbrains.buildServer.vcs.VcsRootInstance rootInstance = myDataProvider.getVcsRootInstance(vcsRootInstanceLocator);
    return VcsRootInstance.getFieldValue(rootInstance, fieldName, myDataProvider);
  }

  @PUT
  @Path("/{vcsRootLocator}/instances/{vcsRootInstanceLocator}/{field}")
  @Consumes("text/plain")
  public void setInstanceField(@PathParam("vcsRootLocator") String vcsRootLocator,
                               @PathParam("vcsRootInstanceLocator") String vcsRootInstanceLocator,
                               @PathParam("field") String fieldName, String newValue) {
    final jetbrains.buildServer.vcs.VcsRootInstance rootInstance = myDataProvider.getVcsRootInstance(vcsRootInstanceLocator);
    VcsRootInstance.setFieldValue(rootInstance, fieldName, newValue, myDataProvider);
    myDataProvider.getVcsManager().persistVcsRoots();
  }


  @GET
  @Path("/{vcsRootLocator}/properties")
  @Produces({"application/xml", "application/json"})
  public Properties serveProperties(@PathParam("vcsRootLocator") String vcsRootLocator) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    return new Properties(vcsRoot.getProperties());
  }

  @PUT
  @Path("/{vcsRootLocator}/properties")
  @Consumes({"application/xml", "application/json"})
  public void changProperties(@PathParam("vcsRootLocator") String vcsRootLocator, Properties properties) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    VcsRoot.updateVCSRoot(vcsRoot, properties.getMap(), null, myDataProvider.getVcsManager());
    myDataProvider.getVcsManager().persistVcsRoots();
  }

  @DELETE
  @Path("/{vcsRootLocator}/properties")
  public void deleteAllProperties(@PathParam("vcsRootLocator") String vcsRootLocator) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    VcsRoot.updateVCSRoot(vcsRoot, new HashMap<String, String>(), null, myDataProvider.getVcsManager());
    myDataProvider.getVcsManager().persistVcsRoots();
  }

  @GET
  @Path("/{vcsRootLocator}/properties/{name}")
  @Produces("text/plain")
  public String serveProperty(@PathParam("vcsRootLocator") String vcsRootLocator, @PathParam("name") String parameterName) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    return BuildTypeUtil.getParameter(parameterName, VcsRoot.getUserParametersHolder(vcsRoot, myDataProvider.getVcsManager()));
  }

  @PUT
  @Path("/{vcsRootLocator}/properties/{name}")
  @Consumes("text/plain")
  public void putParameter(@PathParam("vcsRootLocator") String vcsRootLocator,
                                    @PathParam("name") String parameterName,
                                    String newValue) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    BuildTypeUtil.changeParameter(parameterName, newValue, VcsRoot.getUserParametersHolder(vcsRoot, myDataProvider.getVcsManager()), myServiceLocator);
    myDataProvider.getVcsManager().persistVcsRoots();
  }

  @DELETE
  @Path("/{vcsRootLocator}/properties/{name}")
  @Produces("text/plain")
  public void deleteParameter(@PathParam("vcsRootLocator") String vcsRootLocator,
                                       @PathParam("name") String parameterName) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    BuildTypeUtil.deleteParameter(parameterName, VcsRoot.getUserParametersHolder(vcsRoot, myDataProvider.getVcsManager()));
    myDataProvider.getVcsManager().persistVcsRoots();
  }

  @GET
  @Path("/{vcsRootLocator}/{field}")
  @Produces("text/plain")
  public String serveField(@PathParam("vcsRootLocator") String vcsRootLocator, @PathParam("field") String fieldName) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    return VcsRoot.getFieldValue(vcsRoot, fieldName, myDataProvider);
  }

  @PUT
  @Path("/{vcsRootLocator}/{field}")
  @Consumes("text/plain")
  public void setField(@PathParam("vcsRootLocator") String vcsRootLocator, @PathParam("field") String fieldName, String newValue) {
    final SVcsRoot vcsRoot = myDataProvider.getVcsRoot(vcsRootLocator);
    VcsRoot.setFieldValue(vcsRoot, fieldName, newValue, myDataProvider);
    myDataProvider.getVcsManager().persistVcsRoots();
  }

}