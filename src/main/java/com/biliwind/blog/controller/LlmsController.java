package com.biliwind.blog.controller;

import com.biliwind.blog.service.LlmsService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Serves {@code /llms.txt}, {@code /llms-full.txt}, and {@code /post/{slug}.md}
 * for AI search engines, coding assistants, and LLM crawlers.
 */
@Path("/")
@Blocking
public class LlmsController {

    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";
    private static final String TEXT_MARKDOWN_UTF8 = "text/markdown; charset=utf-8";

    @Inject
    LlmsService llmsService;

    @GET
    @Path("/llms.txt")
    @Produces(TEXT_PLAIN_UTF8)
    public Response llmsTxt() {
        return Response.ok(llmsService.getLlmsTxt(), TEXT_PLAIN_UTF8).build();
    }

    @GET
    @Path("/llms-full.txt")
    @Produces(TEXT_PLAIN_UTF8)
    public Response llmsFullTxt() {
        return Response.ok(llmsService.getLlmsFullTxt(), TEXT_PLAIN_UTF8).build();
    }

    @GET
    @Path("/post/{slug}.md")
    @Produces(TEXT_MARKDOWN_UTF8)
    public Response postMarkdown(@PathParam("slug") String slug) {
        return Response.ok(llmsService.getPostMarkdown(slug), TEXT_MARKDOWN_UTF8)
                .header("Cache-Control", "public, max-age=1800")
                .build();
    }
}
