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

import io.swagger.annotations.ExtensionProperty;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.model.PagerData;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelBaseType;
import jetbrains.buildServer.server.rest.swagger.constants.ExtensionType;
import jetbrains.buildServer.server.rest.swagger.constants.ObjectType;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.DefaultValueAware;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.STest;
import jetbrains.buildServer.util.CollectionsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 * Date: 16.11.13
 */
@SuppressWarnings("PublicField")
@XmlRootElement(name = "tests")
@ModelBaseType(ObjectType.PAGINATED)
public class Tests implements DefaultValueAware {
  @XmlElement(name = "test") public List<Test> items;
  @XmlAttribute public Integer count;
  @XmlElement public TestCounters myTestCounters;
  @XmlAttribute(required = false) @Nullable public String nextHref;
  @XmlAttribute(required = false) @Nullable public String prevHref;

  public Tests() {
  }

  public Tests(@Nullable final Collection<STest> itemsP, @Nullable final PagerData pagerData, @NotNull final BeanContext beanContext, @NotNull final Fields fields) {
    if (itemsP != null) {
      items = ValueWithDefault.decideDefault(
        fields.isIncluded("test", false),
        () -> {
          Fields testFields = fields.getNestedField("test");
          return CollectionsUtil.convertCollection(itemsP, source -> new Test(source, beanContext, testFields));
        });
      this.count = ValueWithDefault.decideDefault(fields.isIncluded("count", true), itemsP.size());
    }

    if (pagerData != null) {
      nextHref = pagerData.getNextHref() == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("nextHref"),
                                                                                         beanContext.getApiUrlBuilder().transformRelativePath(pagerData.getNextHref()));
      prevHref = pagerData.getPrevHref() == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("prevHref"),
                                                                                         beanContext.getApiUrlBuilder().transformRelativePath(pagerData.getPrevHref()));
    }
  }

  public boolean isDefault() {
    return ValueWithDefault.isAllDefault(items, count);
  }

  @NotNull
  public List<STest> getFromPosted(@NotNull final ServiceLocator serviceLocator) {
    //todo: support locator here like in build to allow muting for many tests at once
    if (items == null) {
      throw new BadRequestException("Invalid 'tests' entity: tests should not be empty");
    }
    return items.stream().map(item -> item.getFromPosted(serviceLocator)).collect(Collectors.toList());
  }

  @Nullable
  public TestCounters getTestCounts() {
    return myTestCounters;
  }
}
