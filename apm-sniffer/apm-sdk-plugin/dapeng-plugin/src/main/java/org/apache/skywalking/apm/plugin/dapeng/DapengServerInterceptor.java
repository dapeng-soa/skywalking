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

import com.github.dapeng.core.SoaHeader;
import com.github.dapeng.core.TransactionContext;
import com.github.dapeng.core.filter.FilterChain;
import com.github.dapeng.core.filter.FilterContext;
import com.github.dapeng.core.helper.SoaSystemEnvProperties;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.ContextSnapshot;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.dapeng.define.DapengClientInstrumentation;

import java.lang.reflect.Method;

/**
 * {@link DapengServerInterceptor} define how to enhance class {@link com.github.dapeng.impl.filters.LogFilter#onEntry(FilterContext, FilterChain)} or .
 * {@link com.github.dapeng.impl.filters.LogFilter#onExit(FilterContext, FilterChain)}
 * <p>
 *
 * @author ever
 */
public class DapengServerInterceptor implements InstanceMethodsAroundInterceptor {
    /**
     * key of {@link FilterContext}
     */
    private static final String SW_KEY = "_skywalking";

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        FilterContext filterContext = (FilterContext) allArguments[0];
        AbstractSpan span;

        final TransactionContext transactionContext = (TransactionContext) filterContext.getAttribute("context");
        if (DapengClientInstrumentation.METHOD_ON_ENTRY.equals(method.getName())) {
            ContextCarrier contextCarrier = new ContextCarrier();
            CarrierItem next = contextCarrier.items();
            while (next.hasNext()) {
                next = next.next();
                next.setHeadValue(transactionContext.getHeader().getCookie(next.getHeadKey()));
            }

            String operationName = generateOperationName(transactionContext.getHeader());
            span = ContextManager.createEntrySpan(operationName, contextCarrier);

            Tags.URL.set(span, SoaSystemEnvProperties.HOST_IP + ":" + SoaSystemEnvProperties.SOA_CONTAINER_PORT + "/" + operationName);
            span.setComponent(ComponentsDefine.DUBBO);
            SpanLayer.asRPCFramework(span);

            ContextSnapshot snapshot = ContextManager.capture();
            filterContext.setAttribute(SW_KEY, snapshot);
        } else if (DapengClientInstrumentation.METHOD_ON_EXIT.equals(method.getName())) {
            ContextSnapshot snapshot = (ContextSnapshot) filterContext.getAttribute(SW_KEY);
            ContextManager.continued(snapshot);

            if (transactionContext.soaException() != null) {
                dealException(transactionContext.soaException());
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
    private String generateOperationName(SoaHeader soaHeader) {
        StringBuilder operationName = new StringBuilder();
        operationName.append(soaHeader.getServiceName());
        operationName.append("." + soaHeader.getMethodName() + "(");

        operationName.append(")");

        return operationName.toString();
    }
}
