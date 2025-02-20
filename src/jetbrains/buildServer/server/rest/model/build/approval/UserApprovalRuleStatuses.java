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

package jetbrains.buildServer.server.rest.model.build.approval;

import java.util.List;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.swagger.annotations.ModelBaseType;
import jetbrains.buildServer.server.rest.swagger.constants.ObjectType;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.impl.approval.ApprovalRule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("PublicField")
@XmlRootElement(name = "userApprovals")
@ModelBaseType(
  value = ObjectType.LIST,
  baseEntity = "UserApprovalRule"
)
public class UserApprovalRuleStatuses {
  @XmlAttribute
  public Integer count;

  @XmlElement(name = "userApproval")
  public List<UserApprovalRuleStatus> userApprovalRuleStatuses;

  public UserApprovalRuleStatuses() {
  }

  public UserApprovalRuleStatuses(
    @NotNull BuildPromotionEx buildPromotionEx,
    @Nullable final List<ApprovalRule> rules,
    @NotNull final Fields fields,
    @NotNull final BeanContext beanContext
  ) {
    if (rules != null) {
      userApprovalRuleStatuses = ValueWithDefault.decideDefault(
        fields.isIncluded("userApproval", false, true),
        () -> {
          return rules.stream()
                      .map(rule -> new UserApprovalRuleStatus(buildPromotionEx, rule, fields.getNestedField("userApproval"), beanContext))
                      .collect(Collectors.toList());
        }
      );
      count = ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), rules.size());
    }
  }
}