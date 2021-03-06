
package br.com.conductor.heimdall.gateway.filter;

/*-
 * =========================LICENSE_START==================================
 * heimdall-gateway
 * ========================================================================
 * Copyright (C) 2018 Conductor Tecnologia SA
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ==========================LICENSE_END===================================
 */

import br.com.conductor.heimdall.core.util.Constants;
import br.com.conductor.heimdall.gateway.filter.helper.ApiResponseImpl;
import br.com.conductor.heimdall.gateway.trace.FilterDetail;
import br.com.conductor.heimdall.gateway.trace.TraceContextHolder;
import br.com.conductor.heimdall.gateway.util.ResponseHandler;
import br.com.conductor.heimdall.middleware.spec.ApiResponse;
import br.com.conductor.heimdall.middleware.spec.Helper;
import lombok.extern.slf4j.Slf4j;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static br.com.conductor.heimdall.core.util.ConstantsCache.CACHE_BUCKET;
import static br.com.conductor.heimdall.core.util.ConstantsCache.CACHE_TIME_TO_LIVE;

import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.POST_TYPE;
import static org.springframework.cloud.netflix.zuul.filters.support.FilterConstants.SEND_FORWARD_FILTER_ORDER;

/**
 * Filter to create a cache. When this filter is enabled it will create a cache from the response in Redis.
 *
 * @author Marcelo Aguiar Rodrigues
 */
@Component
@Slf4j
public class CacheWriterFilter extends ZuulFilter {

    private FilterDetail detail = new FilterDetail();

    @Autowired
    private Helper helper;

    @Override
    public String filterType() {
        return POST_TYPE;
    }

    @Override
    public int filterOrder() {
        return SEND_FORWARD_FILTER_ORDER;
    }

    @Override
    public boolean shouldFilter() {
        RequestContext context = RequestContext.getCurrentContext();

        return (context.get(CACHE_BUCKET) != null);
    }

    @Override
    public Object run() {

        long startTime = System.currentTimeMillis();
        try {
            process();
            detail.setStatus(Constants.SUCCESS);
        } catch (Throwable e) {
            detail.setStatus(Constants.FAILED);
            log.error("Error during CacheWriterFilter", e);
        } finally {
            long endTime = System.currentTimeMillis();

            long duration = (endTime - startTime);

            detail.setName(this.getClass().getSimpleName());
            detail.setTimeInMillisRun(duration);
            TraceContextHolder.getInstance().getActualTrace().addFilter(detail);
        }

        return null;
    }

    private void process() throws Throwable {
        RequestContext context = RequestContext.getCurrentContext();

        RBucket<ApiResponse> rBucket = (RBucket<ApiResponse>) context.get(CACHE_BUCKET);

        HttpServletResponse response = context.getResponse();

        Map<String, String> headers = ResponseHandler.getResponseHeaders(context);

        ApiResponse apiResponse = new ApiResponseImpl();
        apiResponse.setHeaders(headers);
        apiResponse.setBody(ResponseHandler.getResponseBody(context, headers, helper));
        apiResponse.setStatus(response.getStatus());

        Long timeToLive = (Long) context.get(CACHE_TIME_TO_LIVE);

        if (timeToLive != null && timeToLive > 0)
            rBucket.set(apiResponse, timeToLive, TimeUnit.MILLISECONDS);
        else
            rBucket.set(apiResponse);

    }

}
