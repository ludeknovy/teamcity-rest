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

package jetbrains.buildServer.server.rest.model;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.containers.SortedList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jetbrains.buildServer.ServiceLocator;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.server.rest.data.util.FilterUtil;
import jetbrains.buildServer.server.rest.data.Locator;
import jetbrains.buildServer.server.rest.data.ParameterCondition;
import jetbrains.buildServer.server.rest.data.parameters.EntityWithModifiableParameters;
import jetbrains.buildServer.server.rest.data.parameters.EntityWithParameters;
import jetbrains.buildServer.server.rest.data.parameters.MapBackedEntityWithParameters;
import jetbrains.buildServer.server.rest.errors.BadRequestException;
import jetbrains.buildServer.server.rest.model.buildType.BuildType;
import jetbrains.buildServer.server.rest.model.buildType.BuildTypeUtil;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelBaseType;
import jetbrains.buildServer.server.rest.swagger.constants.ObjectType;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.BuildTypeOrTemplate;
import jetbrains.buildServer.server.rest.util.DefaultValueAware;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.SimpleParameter;
import jetbrains.buildServer.util.CaseInsensitiveStringComparator;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.*;

/**
 * @author Yegor.Yarko
 *         Date: 13.07.2009
 */
@XmlRootElement(name = "properties")
@ModelBaseType(ObjectType.LIST)
public class Properties  implements DefaultValueAware {
  private static final Logger LOG = Logger.getInstance(Properties.class.getName());
  private static final Comparator<Property> PROPERTY_COMPARATOR = new Comparator<Property>() {
    private final CaseInsensitiveStringComparator comp = new CaseInsensitiveStringComparator();

    public int compare(final Property o1, final Property o2) {
      return comp.compare(o1.name, o2.name);
    }
  };

  protected static final String PROPERTY = "property";
  @XmlAttribute
  public Integer count;

  @XmlAttribute(required = false)
  @Nullable
  public String href;

  @XmlElement(name = PROPERTY)
  public List<Property> properties;

  public Properties() { }

  //todo: review all null usages for href to include due URL
  public Properties(@Nullable final Map<String, String> properties, @Nullable String href, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
    this(properties == null ? null : createEntity(properties, null), href, null, fields, beanContext);
  }

  /**
   * @param externalLocator locator to override the one from "fields"
   */
  public Properties(@Nullable final EntityWithParameters parameters,
                    @Nullable String href,
                    @Nullable Locator externalLocator,
                    @NotNull final Fields fields,
                    @NotNull final BeanContext beanContext) {
    this (parameters, true, href, externalLocator, fields, beanContext);
  }

  /**
   * @param externalLocator locator to override the one from "fields"
   */
  public Properties(@Nullable final EntityWithParameters parameters,
                    boolean enforceSorting,
                    @Nullable String href,
                    @Nullable Locator externalLocator,
                    @NotNull final Fields fields,
                    @NotNull final BeanContext beanContext) {
    if (parameters == null) {
      this.count = null;
      this.properties = null;
    } else {
      if (fields.isIncluded(PROPERTY, false, true)) {
        final Fields propertyFields = fields.getNestedField(PROPERTY, Fields.NONE, Fields.LONG);
        // locator form "fields" is ignored is externalLocator is specified. This is used e.g. in BuildType#getSettings()
        Locator propertiesLocator = externalLocator != null ? externalLocator : (fields.getLocator() == null ? null : new Locator(fields.getLocator()));
        Collection<Parameter> parametersCollection = parameters.getParametersCollection(propertiesLocator); // pass locator here
        final ParameterCondition parameterCondition = ParameterCondition.create(propertiesLocator); // pass locator here and make sure it's used and supported dimensions are preserved
        if (externalLocator == null && propertiesLocator != null) propertiesLocator.checkLocatorFullyProcessed();
        this.properties = enforceSorting ? new SortedList<>(PROPERTY_COMPARATOR) : new ArrayList<>();
        for (Parameter parameter : parametersCollection) {
          Boolean inherited = parameters.isInherited(parameter.getName());
          if (parameterCondition == null || parameterCondition.parameterMatches(parameter, inherited)) {
            this.properties.add(new Property(parameter, inherited, propertyFields, beanContext.getServiceLocator()));
          }
        }
        this.count = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), this.properties.size());  //count of the properties included
      } else {
        this.count = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), parameters.getParametersCollection(null).size()); //actual count when no properties are included
      }
    }
    this.href = href == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("href"), beanContext.getApiUrlBuilder().transformRelativePath(href));
  }

  public Properties(@NotNull final ParametersProvider paramsProvider, @Nullable String href, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
    this.href = href == null ? null : ValueWithDefault.decideDefault(fields.isIncluded("href"), beanContext.getApiUrlBuilder().transformRelativePath(href));

    if (fields.isIncluded(PROPERTY, false, true)) {
      final Fields propertyFields = fields.getNestedField(PROPERTY, Fields.NONE, Fields.LONG);
      Locator propertiesLocator = fields.getLocator() == null ? null : new Locator(fields.getLocator());

      Stream<Parameter> parameters;
      if(propertiesLocator == null) {
        parameters = Properties.convertToSimpleParameters(paramsProvider.getAll()).stream();
      } else {
        final ParameterCondition parameterCondition = ParameterCondition.create(propertiesLocator);
        propertiesLocator.checkLocatorFullyProcessed();

        parameters = parameterCondition.filterAllMatchingParameters(paramsProvider);
      }

      this.properties = parameters.map(parameter -> new Property(parameter, null, propertyFields, beanContext.getServiceLocator()))
                                  .sorted(PROPERTY_COMPARATOR).collect(Collectors.toList());

      this.count = this.properties.size();
    } else {
      this.count = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), paramsProvider.size()); //actual count when no properties are included
    }
  }

  @Nullable
  @Contract("null -> null; !null -> !null")
  public static List<Parameter> convertToSimpleParameters(@Nullable final Map<String, String> parametersMap) {
    if (parametersMap == null ) return null;
    ArrayList<Parameter> result = new ArrayList<>(parametersMap.size());
    for (Map.Entry<String, String> source : parametersMap.entrySet()) {
      if (source.getValue() == null)
        continue;
      result.add(new SimpleParameter(source.getKey(), source.getValue()));
    }
    return result;
//    return parametersMap.entrySet().stream().filter(entry ->  entry.getValue() != null).map(entry -> new SimpleParameter(entry.getKey(), entry.getValue())).collect(Collectors.toList());
  }

  @NotNull
  public Map<String, String> getMap() {
    return getMap(null);
  }

  @NotNull
  public Map<String, String> getMap(final Boolean ownOnly) {
    if (properties == null) {
      return new HashMap<String, String>();
    }
    final HashMap<String, String> result = new HashMap<String, String>(properties.size());
    for (Property property : properties) {
      boolean actualOwn =  property.inherited == null || !property.inherited;
      if (FilterUtil.isIncludedByBooleanFilter(ownOnly, actualOwn)){
        property.isValid();//todo  check for unused type, inherited.
        result.put(property.name, property.value);
      }
    }
    return result;
  }

  public boolean isDefault() {
    return ValueWithDefault.isAllDefault(count, href, properties);
  }

  public boolean setTo(@NotNull final BuildTypeOrTemplate buildType, @NotNull final ServiceLocator serviceLocator) {
    return setTo(BuildType.createEntity(buildType), serviceLocator);
  }

  public boolean setTo(@NotNull final EntityWithModifiableParameters holder,
                       @NotNull final ServiceLocator serviceLocator) {
    Collection<Parameter> ownParameters = holder.getOwnParametersCollection();
    Collection<Parameter> original = ownParameters != null ? ownParameters : holder.getParametersCollection(null);
    try {
      BuildTypeUtil.removeAllParameters(holder);
      if (properties != null) {
        for (Property entity : properties) {
          entity.addTo(holder, serviceLocator);
        }
      }
      return true;
    } catch (Exception e) {
      //restore all parameters if setting one has failed
      BuildTypeUtil.removeAllParameters(holder);
      for (Parameter p : original) {
        holder.addParameter(p);
      }
      throw new BadRequestException("Cannot set parameters: " + e.toString(), e);
    }
  }

  @Nullable
  private Set<String> getNames(@Nullable final Collection<Parameter> parameters) {
    if (parameters == null) return null;
    final HashSet<String> result = new HashSet<>(parameters.size());
    for (Parameter parameter : parameters) {
      result.add(parameter.getName());
    }
    return result;
  }

  public boolean isSimilar(final Properties that) {
    return that != null &&
          (count == null || that.count == null || com.google.common.base.Objects.equal(count, that.count)) &&
          (properties == null || that.properties == null || com.google.common.base.Objects.equal(properties, that.properties));
  }

  @NotNull
  public static EntityWithParameters createEntity(@NotNull final Map<String, String> params, @Nullable final Map<String, String> ownParams) {
    return new MapBackedEntityWithParameters(params, ownParams);
  }

}
