package com.biliwind.blog.filter;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.RegionRuleService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.List;

/**
 * 区域识别过滤器，在请求进入时解析区域并注入 RegionContext
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class RegionFilter implements ContainerRequestFilter {

    @Inject
    RegionRuleService regionRuleService;

    @Inject
    RegionContext regionContext;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String host = requestContext.getHeaderString("Host");

        List<String> languages = requestContext.getAcceptableLanguages()
                .stream()
                .map(java.util.Locale::toLanguageTag)
                .toList();

        BlogRegion region = regionRuleService.resolveRegion(host, languages);
        regionContext.setCurrentRegion(region);
    }
}
