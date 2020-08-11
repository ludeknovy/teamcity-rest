/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

package jetbrains.buildServer.server.rest.swagger;

import com.intellij.openapi.util.io.StreamUtil;
import io.swagger.annotations.Api;
import jetbrains.buildServer.server.rest.SwaggerUIUtil;
import jetbrains.buildServer.server.rest.request.Constants;

import javax.imageio.ImageIO;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Path(Constants.API_URL + "/swaggerui")
@Api(hidden = true)
public class SwaggerUI {

  private static final Pattern TRAILING_SLASH_PATTERN = Pattern.compile("/*$");

  @Context
  private UriInfo myUri;

  @GET
  @Produces({MediaType.TEXT_HTML})
  public InputStream serveSwaggerUI() {
    String host = TRAILING_SLASH_PATTERN.matcher(myUri.getBaseUri().toString()).replaceFirst("");

    Map<String, String> replacementMap = new HashMap<String, String>();
    replacementMap.put(
        "https://petstore.swagger.io/v2/swagger.json",
        host + Constants.API_URL + "/swagger.json"
    );

    try (InputStream input = SwaggerUIUtil.readIndexAndSubstituteText(replacementMap)) {
      return input;
    } catch (IOException e) {
      throw new UncheckedIOException("Error while retrieving Swagger UI", e);
    }
  }

  @GET
  @Path("/{path:.*}")
  public Object serveSwaggerResource(@PathParam("path") String path) {
    if (path.equals(SwaggerUIUtil.UI_FILE)) {
      return serveSwaggerUI();
    }

    try (InputStream input = SwaggerUIUtil.getFileFromResources(path)) {
      if (path.endsWith(".js") || path.endsWith(".css")) {
        String response = StreamUtil.readText(input, "UTF-8");
        return response;
      }
      else if (path.endsWith(".png")) {
        BufferedImage response = ImageIO.read(input);
        return response;
      }
      return input;
    } catch (IOException e) {
      throw new UncheckedIOException(String.format("Error while retrieving Swagger UI element %s", path), e);
    }
  }

}
