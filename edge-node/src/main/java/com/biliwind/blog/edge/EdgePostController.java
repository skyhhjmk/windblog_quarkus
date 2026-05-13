package com.biliwind.blog.edge;

import com.biliwind.blog.edge.model.Post;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/posts")
@Produces(MediaType.APPLICATION_JSON)
public class EdgePostController {

    @jakarta.inject.Inject
    EdgeCacheService cacheService;

    @GET
    public List<Post> list(@QueryParam("page") @DefaultValue("0") int page,
                           @QueryParam("size") @DefaultValue("10") int size) {
        return Post.find("isPublished = true").page(page, size).list();
    }

    @GET
    @Path("/{slug}")
    public Post getBySlug(@PathParam("slug") String slug) {
        // Try cache first
        Post post = cacheService.getPost(slug);

        if (post == null) {
            post = Post.find("slug = ?1 AND isPublished = true", slug).firstResult();
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
