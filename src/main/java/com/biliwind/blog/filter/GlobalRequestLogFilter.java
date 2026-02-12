package com.biliwind.blog.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

// @Provider 标记为JAX-RS组件，Quarkus自动注册为全局过滤器
@Provider
public class GlobalRequestLogFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(GlobalRequestLogFilter.class);

    @Context
    UriInfo uriInfo;

    // 请求前置处理：记录请求方法、路径、参数
    @Override
    public void filter(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();
        String path = uriInfo.getPath();
        LOG.info("Quarkus 中间件：收到请求 -> 方法：" + method + "，路径：" + path);
    }
}