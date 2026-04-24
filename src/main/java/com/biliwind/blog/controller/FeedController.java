package com.biliwind.blog.controller;

import com.biliwind.blog.service.FeedService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/")
public class FeedController {

    @Inject
    FeedService feedService;

    @ConfigProperty(name = "blog.title")
    String blogTitle;

    @ConfigProperty(name = "blog.description")
    String blogDescription;

    @Inject
    @Location("feeds/sitemap-index.xml")
    Template sitemapIndexTemplate;

    @Inject
    @Location("feeds/sitemap-posts.xml")
    Template sitemapPostsTemplate;

    @Inject
    @Location("feeds/sitemap-categories.xml")
    Template sitemapCategoriesTemplate;

    @Inject
    @Location("feeds/sitemap-tags.xml")
    Template sitemapTagsTemplate;

    @Inject
    @Location("feeds/rss.xml")
    Template rssTemplate;

    @GET
    @Path("/sitemap.xml")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance sitemapIndex() {
        return sitemapIndexTemplate
                .data("baseUrl", feedService.getBaseUrl());
    }

    @GET
    @Path("/sitemap-posts.xml")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance sitemapPosts() {
        return sitemapPostsTemplate
                .data("baseUrl", feedService.getBaseUrl())
                .data("posts", feedService.getPublishedPosts());
    }

    @GET
    @Path("/sitemap-categories.xml")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance sitemapCategories() {
        return sitemapCategoriesTemplate
                .data("baseUrl", feedService.getBaseUrl())
                .data("items", feedService.getCategorySitemapUrls());
    }

    @GET
    @Path("/sitemap-tags.xml")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance sitemapTags() {
        return sitemapTagsTemplate
                .data("baseUrl", feedService.getBaseUrl())
                .data("items", feedService.getTagSitemapUrls());
    }

    @GET
    @Path("/rss.xml")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance rss() {
        return rssTemplate
                .data("baseUrl", feedService.getBaseUrl())
                .data("blogTitle", blogTitle)
                .data("blogDescription", blogDescription)
                .data("lastBuildDate", feedService.getCurrentRfc822Date())
                .data("posts", feedService.getPublishedPosts());
    }

    @GET
    @Path("/feed")
    @Produces(MediaType.APPLICATION_XML)
    public TemplateInstance feed() {
        return rss();
    }
}
