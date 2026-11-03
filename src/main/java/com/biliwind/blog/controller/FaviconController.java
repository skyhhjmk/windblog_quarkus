package com.biliwind.blog.controller;

import com.biliwind.blog.service.ConfigManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Routes common favicon filenames to the image selected in Appearance settings. */
@Path("/")
public class FaviconController {

    private static final Set<String> FAVICON_PATHS = Set.of(
            "/favicon.ico", "/favicon.jpg", "/favicon.jpeg", "/favicon.jpe", "/favicon.png",
            "/favicon.webp", "/favicon.svg", "/favicon.gif", "/favicon.avif",
            "/favicon.bmp", "/favicon.tif", "/favicon.tiff");

    @Inject
    ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.FaviconResolverService faviconResolverService;

    @GET
    @Path("/{fileName: favicon\\.(ico|jpg|jpeg|jpe|png|webp|svg|gif|avif|bmp|tif|tiff)}")
    public Response getFavicon(@PathParam("fileName") String fileName) {
        String target = faviconResolverService != null
                ? faviconResolverService.resolveFaviconUrl()
                : configManager.getString("appearance", "favicon_url", "").trim();

        if (target == null || target.isBlank()) {
            if ("favicon.svg".equalsIgnoreCase(fileName) || "favicon.png".equalsIgnoreCase(fileName)) {
                throw new NotFoundException();
            }
            return Response.status(Response.Status.FOUND)
                    .location(URI.create("/favicon.svg"))
                    .header("Cache-Control", "public, max-age=86400")
                    .build();
        }

        URI targetUri;
        try {
            targetUri = URI.create(target);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException();
        }
        if (isFaviconAlias(targetUri.getPath()) || !isAllowedTarget(targetUri)) {
            throw new NotFoundException();
        }

        return Response.status(Response.Status.FOUND)
                .location(targetUri)
                .header("Cache-Control", "no-cache, max-age=0, must-revalidate")
                .build();
    }

    private boolean isFaviconAlias(String targetPath) {
        String normalized = targetPath == null ? "" : targetPath.toLowerCase(Locale.ROOT);
        return FAVICON_PATHS.contains(normalized);
    }

    private boolean isAllowedTarget(URI target) {
        if (target.isAbsolute()) {
            String scheme = target.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && target.getHost() != null
                    && target.getUserInfo() == null;
        }
        String path = target.getRawPath();
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && target.getRawAuthority() == null;
    }
}
