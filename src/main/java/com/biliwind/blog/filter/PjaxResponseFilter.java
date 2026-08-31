package com.biliwind.blog.filter;

import com.biliwind.blog.common.helper.PjaxHelper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

/** Prevents fragment responses from being reused as full HTML documents. */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class PjaxResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(requestContext.getHeaders());
        if (!pjaxRequest && !isHtmlResponse(responseContext)) {
            return;
        }

        MultivaluedMap<String, Object> headers = responseContext.getHeaders();
        appendVary(headers, PjaxHelper.PJAX_HEADER);
        if (pjaxRequest) {
            applyNoStore(headers);
        }
    }

    static boolean isHtmlResponse(ContainerResponseContext responseContext) {
        if (responseContext == null) {
            return false;
        }
        MediaType mediaType = responseContext.getMediaType();
        return mediaType == null || mediaType.isCompatible(MediaType.TEXT_HTML_TYPE);
    }

    static void appendVary(MultivaluedMap<String, Object> headers, String value) {
        if (headers == null || value == null || value.isBlank()) {
            return;
        }

        String existingVary = headers.getFirst("Vary") == null
                ? ""
                : headers.getFirst("Vary").toString();
        String[] varyValues = existingVary.split(",");
        for (String varyValue : varyValues) {
            if (value.equalsIgnoreCase(varyValue.trim())) {
                return;
            }
        }

        String nextVary = existingVary.isBlank() ? value : existingVary + ", " + value;
        headers.putSingle("Vary", nextVary);
    }

    static void applyNoStore(MultivaluedMap<String, Object> headers) {
        if (headers == null) {
            return;
        }
        headers.putSingle("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.putSingle("Pragma", "no-cache");
        headers.putSingle("Expires", "0");
    }
}
