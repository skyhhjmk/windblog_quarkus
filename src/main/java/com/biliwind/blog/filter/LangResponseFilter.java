package com.biliwind.blog.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.biliwind.blog.common.constant.LanguageConstant.COOKIE_LANG_KEY;

@Provider
public class LangResponseFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String lang = (String) requestContext.getProperty(LangRequestFilter.LANG_ATTRIBUTE);
        boolean explicit = Boolean.TRUE.equals(
                requestContext.getProperty(LangRequestFilter.LANG_EXPLICIT_ATTRIBUTE));

        boolean cookieNeedsUpdate = false;
        if (lang != null && !lang.isEmpty() && explicit) {
            String encodedLang = URLEncoder.encode(lang, StandardCharsets.UTF_8);
            Cookie existingCookie = requestContext.getCookies().get(COOKIE_LANG_KEY);
            if (existingCookie == null || !encodedLang.equals(existingCookie.getValue())) {
                cookieNeedsUpdate = true;
                NewCookie langCookie = new NewCookie.Builder(COOKIE_LANG_KEY)
                        .value(encodedLang)
                        .path("/")
                        .maxAge(86400 * 30)
                        .secure(cookieSecure)
                        .httpOnly(true)
                        .sameSite(NewCookie.SameSite.LAX)
                        .build();
                responseContext.getHeaders().add(HttpHeaders.SET_COOKIE, langCookie);
            }
        }

        if (CsrfFilter.isCacheableHtmlResponse(responseContext.getMediaType(),
                responseContext.getHeaderString(HttpHeaders.CACHE_CONTROL))) {
            // Language and authentication cookies affect public HTML. Do not let a
            // shared cache serve one cookie context to another visitor.
            responseContext.getHeaders().putSingle(HttpHeaders.VARY, "Cookie");
            if (cookieNeedsUpdate) {
                responseContext.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
            }
        }
    }
}
