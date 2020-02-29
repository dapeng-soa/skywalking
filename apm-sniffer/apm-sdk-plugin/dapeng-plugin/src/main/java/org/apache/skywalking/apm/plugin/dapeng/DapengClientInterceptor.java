/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.dapeng;

import java.lang.reflect.Method;

import com.github.dapeng.client.netty.SoaBaseConnection;
import com.github.dapeng.core.InvocationContext;
import com.github.dapeng.core.filter.FilterChain;
import com.github.dapeng.core.filter.FilterContext;
import com.github.dapeng.core.SoaHeader;
import com.github.dapeng.core.helper.SoaSystemEnvProperties;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextSnapshot;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.dapeng.define.DapengClientInstrumentation;

/**
 * {@link DapengClientInterceptor} define how to enhance class {@link com.github.dapeng.client.filter.LogFilter#onEntry(FilterContext, FilterChain)} or
 * {@link com.github.dapeng.client.filter.LogFilter#onExit(FilterContext, FilterChain)}.
 * <p>
 * the trace context transport to the provider side by {@link SoaHeader#cookies}.
 */
public class DapengClientInterceptor implements InstanceMethodsAroundInterceptor {
    /**
     * key of {@link FilterContext}
     */
    private static final String SW_KEY = "_skywalking";

    /**
     * <h2>Consumer:</h2> The serialized trace context data will
     * inject to the {@link SoaHeader#cookies} for transport to provider side.
     *
     * <h2>Provider:</h2> The serialized trace context data will extract from
     * {@link SoaHeader#cookies}. current trace segment will ref if the serialize context data is not null.
     */
    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        FilterContext filterContext = (FilterContext) allArguments[0];
        AbstractSpan span;

        // ip:port
        final String serverInfo = (String) filterContext.getAttribute("serverInfo");
        final InvocationContext invocationContext = (InvocationContext) filterContext.getAttribute("context");
        if (DapengClientInstrumentation.METHOD_ON_ENTRY.equals(method.getName())) {
            final ContextCarrier contextCarrier = new ContextCarrier();
            final String operationName = generateOperationName(invocationContext);
            span = ContextManager.createExitSpan(operationName, contextCarrier, serverInfo);
            CarrierItem next = contextCarrier.items();
            while (next.hasNext()) {
                next = next.next();
                invocationContext.setCookie(next.getHeadKey(), next.getHeadValue());
            }
            Tags.URL.set(span, serverInfo + "/" + operationName);
            span.setComponent(ComponentsDefine.DAPENG);
            SpanLayer.asRPCFramework(span);

            ContextSnapshot snapshot = ContextManager.capture();
            filterContext.setAttribute(SW_KEY, snapshot);
        } else if (DapengClientInstrumentation.METHOD_ON_EXIT.equals(method.getName())) {
            ContextSnapshot snapshot = (ContextSnapshot) filterContext.getAttribute(SW_KEY);
            ContextManager.continued(snapshot);

            if (!SoaSystemEnvProperties.SOA_NORMAL_RESP_CODE.equals(invocationContext.lastInvocationInfo().responseCode())) {
                SoaBaseConnection.Result rpcResult = (SoaBaseConnection.Result) filterContext.getAttribute("result");
                if (rpcResult.exception != null) {
                    dealException(rpcResult.exception);
                }
            }

            ContextManager.stopSpan();
        }
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        dealException(t);
    }

    /**
     * Log the throwable, which occurs in Dubbo RPC service.
     */
    private void dealException(Throwable throwable) {
        AbstractSpan span = ContextManager.activeSpan();
        span.errorOccurred();
        span.log(throwable);
    }

    /**
     * Format operation name. e.g. org.apache.skywalking.apm.plugin.test.Test.test
     *
     * @return operation name.
     */
    private String generateOperationName(InvocationContext invocationCtx) {
        StringBuilder operationName = new StringBuilder();
        operationName.append(invocationCtx.serviceName());
        operationName.append("." + invocationCtx.methodName() + "(");

        operationName.append(")");

        return operationName.toString();
    }
}
