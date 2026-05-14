package com.biliwind.blog.edge;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/posts")
@Produces(MediaType.APPLICATION_JSON)
public class EdgePostController {

    @jakarta.inject.Inject
    EdgeCacheService cacheService;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;

    @GET
    public List<Post> list(@QueryParam("page") @DefaultValue("0") int page,
                           @QueryParam("size") @DefaultValue("10") int size) {
        return Post.find("status = ?1 and (visibilityRegions is null or cast(visibilityRegions as String) like ?2)",
                PostStatus.PUBLISHED, "%\"" + region + "\"%").page(page, size).list();
    }

    @GET
    @Path("/{slug}")
    public Post getBySlug(@PathParam("slug") String slug) {
        // Try cache first
        Post post = cacheService.getPost(slug);

        if (post == null) {
            post = Post.find("slug = ?1 AND status = ?2 and (visibilityRegions is null or cast(visibilityRegions as String) like ?3)",
                    slug, PostStatus.PUBLISHED, "%\"" + region + "\"%").firstResult();
            if (post != null) {
                cacheService.setPost(post);
            }
        }

        if (post == null) {
            throw new NotFoundException();
        }
        return post;
    }
}
