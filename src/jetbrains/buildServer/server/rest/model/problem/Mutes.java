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

package jetbrains.buildServer.server.rest.model.problem;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.finder.impl.MuteFinder;
import jetbrains.buildServer.server.rest.data.problem.MuteData;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelBaseType;
import jetbrains.buildServer.server.rest.swagger.constants.ObjectType;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.DefaultValueAware;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.mute.MuteInfo;
import jetbrains.buildServer.util.CollectionsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 * @since 16.11.13
 */
@SuppressWarnings("PublicField")
@XmlRootElement(name = "mutes")
@ModelBaseType(ObjectType.PAGINATED)
public class Mutes implements DefaultValueAware{

  public static final String REST_MUTES_ACTUAL_STATE = "rest.mutes.actual.state"; // Setting this internal property fixes TW-53288 but makes requests slower

  @XmlAttribute public Integer count;
  @XmlAttribute(required = false) @Nullable public String nextHref;
  @XmlAttribute(required = false) @Nullable public String prevHref;
  @XmlAttribute(name = "href") public String href;

  @XmlElement(name = "mute") public List<Mute> items;

  private boolean isDefault;


  /**
   * This method creates Mutes object with attributes which are actual to the current moment, not the moment when the mute was initially created.
   * Unfortunately, this includes loading of all mutes for the Root project, so this method is really slow.
   */
  @NotNull
  static Mutes createMutesWithActualAttributes(@NotNull String problemLocator, @NotNull Fields fields, @NotNull BeanContext beanContext) {
    Fields nestedFields = fields.getNestedField("mutes", Fields.NONE, Fields.LONG);
    final String actualLocatorText = Locator.merge(nestedFields.getLocator(), problemLocator);
    List<MuteInfo> entries = beanContext.getSingletonService(MuteFinder.class).getItems(actualLocatorText).getEntries();
    return new Mutes(entries, null, nestedFields, beanContext);
  }

  public Mutes() {
  }

  public Mutes(@Nullable final Collection<MuteInfo> itemsP,
               @Nullable final PagerData pagerData, //todo: not nulls are not yet implemented
               @NotNull final Fields fields,
               @NotNull final BeanContext beanContext) {
    if (itemsP != null) {
      items = ValueWithDefault.decideDefault(
        fields.isIncluded("mute", false), () -> CollectionsUtil.convertCollection(itemsP, source ->
          new Mute(source, fields.getNestedField("mute", Fields.NONE, Fields.LONG), beanContext)
        )
      );
    }

    if (pagerData != null) {
      href = ValueWithDefault.decideDefault(fields.isIncluded("href"), beanContext.getApiUrlBuilder().transformRelativePath(pagerData.getHref()));
      nextHref = pagerData.getNextHref() == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("nextHref"),
                                                                                         beanContext.getApiUrlBuilder().transformRelativePath(pagerData.getNextHref()));
      prevHref = pagerData.getPrevHref() == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("prevHref"),
                                                                                         beanContext.getApiUrlBuilder().transformRelativePath(pagerData.getPrevHref()));
    }
    count = itemsP == null ? null : ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count", true), itemsP.size());

    if (itemsP != null && itemsP.isEmpty()) {
      isDefault = true;
    } else {
      isDefault = ValueWithDefault.isAllDefault(count, href, itemsP);
    }
  }

  public boolean isDefault() {
    return isDefault;
  }

  public List<MuteData> getFromPosted(@NotNull final ServiceLocator serviceLocator) {
    if (items == null) {
      throw new BadRequestException("Invalid 'mutes' entity: 'items' should be specified");
    }
    return items.stream().map(mute -> mute.getFromPosted(serviceLocator)).collect(Collectors.toList());
  }

}
