package com.biliwind.blog.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class GlobalRequestLogFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(GlobalRequestLogFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();
        String path = requestContext.getUriInfo().getPath();
        LOG.info("Logger: Get a request -> method is: " + method + ",path is: " + path);
    }
}