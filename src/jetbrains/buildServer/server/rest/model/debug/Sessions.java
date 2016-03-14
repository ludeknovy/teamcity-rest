/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

package jetbrains.buildServer.server.rest.model.debug;

import com.intellij.openapi.diagnostic.Logger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import javax.management.*;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.server.rest.errors.OperationException;
import jetbrains.buildServer.server.rest.model.Fields;
import jetbrains.buildServer.server.rest.util.BeanContext;
import jetbrains.buildServer.server.rest.util.ValueWithDefault;
import jetbrains.buildServer.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Yegor.Yarko
 *         Date: 24/12/2015
 */
@SuppressWarnings("PublicField")
@XmlRootElement(name = "sessions")
public class Sessions {
  private final Logger LOG = Logger.getInstance(Sessions.class.getName());

  @XmlAttribute public Integer count;

  @XmlAttribute public Long maxActive;
  @XmlAttribute public Long sessionCounter;
  @XmlAttribute public Long sessionCreateRate;
  @XmlAttribute public Long sessionExpireRate;
  @XmlAttribute public Long sessionMaxAliveTime;

  @XmlElement(name = "session")
  public Collection<Session> sessions;

  protected static final String[] SIGNATURE_SESSION_ATTRIBUTE = new String[]{String.class.getName(), String.class.getName()};
  //see jetbrains.buildServer.web.util.SessionUser.DEFAULT_USER_KEY
  protected static final String SESSION_USER_KEY_ATTRIBUTE_NAME = "USER_KEY";

  @SuppressWarnings("unused")
  public Sessions() {
  }

  public Sessions(@NotNull final MBeanServer serverBean, @NotNull final ObjectName managerBean, @NotNull final Fields fields, @NotNull final BeanContext beanContext) {
//    activeSessions = retrieve(serverBean, managerBean, fields, "activeSessions");
    maxActive = retrieve(serverBean, managerBean, fields, "maxActive");
    sessionCounter = retrieve(serverBean, managerBean, fields, "sessionCounter");
    sessionCreateRate = retrieve(serverBean, managerBean, fields, "sessionCreateRate"); //todo: why not present???
    sessionExpireRate = retrieve(serverBean, managerBean, fields, "sessionExpireRate");  //todo: why not present???
    sessionMaxAliveTime = retrieve(serverBean, managerBean, fields, "sessionMaxAliveTime");  //todo: why not present???

    sessions = ValueWithDefault.decideDefault(fields.isIncluded("session", true), new ValueWithDefault.Value<Collection<Session>>() {
      @Nullable
      @Override
      public Collection<Session> get() {
        return getSessions(serverBean, managerBean, fields.getNestedField("session", Fields.LONG, Fields.LONG), beanContext);  //todo: long here?
      }
    });
    count = sessions == null ? null : ValueWithDefault.decideIncludeByDefault(fields.isIncluded("count"), sessions.size());
  }

  @NotNull
  private Collection<Session> getSessions(@NotNull final MBeanServer serverBean, @NotNull final ObjectName managerBean,
                                          @NotNull final Fields fields, @NotNull final BeanContext beanContext) {

    String sessionsListRaw;
    try {
      sessionsListRaw = String.valueOf(serverBean.invoke(managerBean, "listSessionIds", null, null));
    } catch (Exception e) {
      throw new OperationException("Could not get sessions data: " + e.toString(), e);
    }
    final String[] sessionIds = sessionsListRaw.split(" ");
    final ArrayList<Session> result = new ArrayList<Session>(sessionIds.length);
    for (String sessionId : sessionIds) {
      Long userId;
      try {
        userId = getSessionUserId(serverBean, managerBean, sessionId);
      } catch (Exception e) {
        userId = null;
        LOG.debug("Cannot retrieve userId for session with id id '" + sessionId + "': " + e.toString());
      }
      Long creationTimestamp = (Long)getBeanOperationResult(serverBean, managerBean, sessionId, "getCreationTimestamp");
      Long lastAccessedTimestamp = (Long)getBeanOperationResult(serverBean, managerBean, sessionId, "getLastAccessedTimestamp");
      result.add(new Session(sessionId, userId,
                             creationTimestamp == null ? null : new Date(creationTimestamp), lastAccessedTimestamp == null ? null : new Date(lastAccessedTimestamp),
                             fields, beanContext));
    }
    return result;  //todo: sort!
  }

  private Object getBeanOperationResult(final MBeanServer serverBean, final ObjectName managerBean, final String sessionId, final String operationName) {
    try {
      return serverBean.invoke(managerBean, operationName, new Object[]{sessionId}, new String[]{String.class.getName()});
    } catch (Exception e) {
      return null;
    }
  }

  private long getSessionUserId(final @NotNull MBeanServer serverBean, final @NotNull ObjectName managerBean, final String sessionId) throws Exception {
    final String userKeyAttribute = getUserKeySessionAttribute(serverBean, managerBean, sessionId);
    if (!StringUtil.isEmpty(userKeyAttribute)) {
      final int index = userKeyAttribute.lastIndexOf("{id=");
      if (userKeyAttribute.endsWith("}") && index >= 0) {
        final String userId = userKeyAttribute.substring(index + "{id=".length(), userKeyAttribute.length() - "}".length());
        if (StringUtil.isEmpty(userId)) {
          throw new Exception("Parsed user id is empty");
        }
        return Long.valueOf(userId);
      }
      throw new Exception("Unparsable attribute value '" + userKeyAttribute + "'");
    }
    throw new Exception("No '" + SESSION_USER_KEY_ATTRIBUTE_NAME + "' session attribute found");
  }

  @Nullable
  private String getUserKeySessionAttribute(final @NotNull MBeanServer serverBean, final @NotNull ObjectName managerBean, final String sessionId)
    throws InstanceNotFoundException, MBeanException, ReflectionException {
    return (String)serverBean.invoke(managerBean, "getSessionAttribute", new Object[]{sessionId, SESSION_USER_KEY_ATTRIBUTE_NAME}, SIGNATURE_SESSION_ATTRIBUTE);
  }

  private Long retrieve(@NotNull final MBeanServer serverBean, @NotNull final ObjectName managerBean, @NotNull final Fields fields, @NotNull final String attributeName) {
    return ValueWithDefault.decideDefault(fields.isIncluded(attributeName), new ValueWithDefault.Value<Long>() {
      @Nullable
      @Override
      public Long get() {
        final Object result = getBeanAttribute(serverBean, managerBean, attributeName);
        if (result == null) {
          return null;
        }
        if (result instanceof Integer) {
          return Long.valueOf((Integer)result);
        }
        if (result instanceof Long) {
          return Long.valueOf((Long)result);
        }
        throw new OperationException("Cannot retrieve numeric value of " + result);
      }
    });
  }

  private static Object getBeanAttribute(@NotNull final MBeanServer serverBean, @NotNull final ObjectName managerBean, @NotNull final String attributeName) {
    try {
      return serverBean.getAttribute(managerBean, attributeName);
    } catch (Exception e) {
      return null;
    }
  }
}