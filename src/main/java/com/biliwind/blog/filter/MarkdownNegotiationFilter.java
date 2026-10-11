package com.biliwind.blog.filter;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Negotiates public pages before routing, preserving the existing access checks. */
@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class MarkdownNegotiationFilter implements ContainerRequestFilter {
    private static final String MARKDOWN_REQUEST = MarkdownNegotiationFilter.class.getName();

    @Override
    public void filter(ContainerRequestContext request) {
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && isPublicPage(request.getUriInfo().getPath())
                && prefersMarkdown(request.getHeaderString("Accept"))) {
            request.setProperty(MARKDOWN_REQUEST, true);
            // Controllers and Qute continue to produce their normal authorized HTML.
            request.getHeaders().putSingle("Accept", "text/html");
        }
    }

    static boolean isPublicPage(String path) {
        String normalized = path == null ? "" : path.replaceFirst("^/", "");
        return normalized.matches("(?:|page/[0-9]+|(?:[^/]+/)?(?:amp/)?post/[^/]+|(?:category|tag)(?:/[^/]+)?|search|link(?:/(?:apply|[0-9]+|go/[^/]+))?|terms|privacy)");
    }

    static boolean prefersMarkdown(String accept) {
        if (accept == null) {
            return false;
        }
        double markdown = -1;
        double html = -1;
        for (String item : accept.split(",")) {
            try {
                MediaType type = MediaType.valueOf(item.trim());
                double quality = Double.parseDouble(type.getParameters().getOrDefault("q", "1"));
                if (!Double.isFinite(quality) || quality < 0 || quality > 1) {
                    continue;
                }
                if ("text".equalsIgnoreCase(type.getType()) && "markdown".equalsIgnoreCase(type.getSubtype())) {
                    markdown = Math.max(markdown, quality);
                } else if (type.isCompatible(MediaType.TEXT_HTML_TYPE)) {
                    html = Math.max(html, quality);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed media ranges rather than turning them into a 500.
            }
        }
        return markdown > 0 && markdown >= html;
    }

    @ServerResponseFilter
    public Uni<Void> response(ContainerRequestContext request, ContainerResponseContext response) {
        if (!isPublicPage(request.getUriInfo().getPath()) || !PjaxResponseFilter.isHtmlResponse(response)) {
            return Uni.createFrom().voidItem();
        }
        PjaxResponseFilter.appendVary(response.getHeaders(), "Accept");
        if (!Boolean.TRUE.equals(request.getProperty(MARKDOWN_REQUEST)) || !response.hasEntity()) {
            return Uni.createFrom().voidItem();
        }
        Object entity = response.getEntity();
        Uni<String> html;
        if (entity instanceof TemplateInstance template) {
            html = Uni.createFrom().completionStage(template::renderAsync);
        } else if (entity instanceof String text) {
            html = Uni.createFrom().item(text);
        } else {
            return Uni.createFrom().voidItem();
        }
        return html.invoke(text -> {
            response.setEntity(toMarkdown(text), response.getEntityAnnotations(),
                    MediaType.valueOf("text/markdown; charset=utf-8"));
            for (String header : new String[]{"Content-Length", "Content-Encoding", "ETag", "Last-Modified", "Content-Range"}) {
                response.getHeaders().remove(header);
            }
        }).replaceWithVoid();
    }

    static String toMarkdown(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, nav, footer, aside, form, button, template, noscript, [hidden], [aria-hidden=true]").remove();
        Element main = document.selectFirst("main");
        Element content = main == null ? document.body() : main;
        return FlexmarkHtmlConverter.builder().build().convert(content.html()).strip() + "\n";
    }
}
