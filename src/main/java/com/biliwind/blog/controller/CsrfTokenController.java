package com.biliwind.blog.controller;

import com.biliwind.blog.common.security.CsrfTokenManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * Issues CSRF double-submit cookies on demand so static-cached public HTML
 * pages never emit Set-Cookie headers.
 */
@Path("/api/csrf")
@Produces(MediaType.APPLICATION_JSON)
public class CsrfTokenController {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    @Inject
    CsrfTokenManager csrfTokenManager;

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @GET
    public Response getCsrfToken(@Context HttpHeaders headers) {
        Cookie existing = headers != null ? headers.getCookies().get(CSRF_COOKIE_NAME) : null;
        String token = (existing != null && existing.getValue() != null && !existing.getValue().isBlank())
                ? existing.getValue()
                : csrfTokenManager.generateToken();

        Response.ResponseBuilder builder = Response.ok(Map.of("success", true, "token", token))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache");

        if (existing == null || existing.getValue() == null || existing.getValue().isBlank()) {
            NewCookie cookie = new NewCookie.Builder(CSRF_COOKIE_NAME)
                    .value(token)
                    .path("/")
                    .httpOnly(false)
                    .secure(cookieSecure)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build();
            builder.cookie(cookie);
        }
        return builder.build();
    }
}
