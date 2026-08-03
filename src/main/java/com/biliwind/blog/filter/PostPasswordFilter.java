package com.biliwind.blog.filter;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.service.PostAccessPolicy;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * 文章密码过滤器
 * 用于验证密码保护的文章的访问权限
 */
@Provider
@PasswordProtected
@Priority(Priorities.AUTHENTICATION)
public class PostPasswordFilter implements ContainerRequestFilter {

    @Inject
    @Location("blog/password.html")
    Template passwordTemplate;

    @Inject
    @Location("blog/password.content.html")
    Template passwordContentTemplate;

    @Inject
    PostAccessPolicy postAccessPolicy;

    @Inject
    UserTokenVerifier userTokenVerifier;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {

        String slug = ctx.getUriInfo()
                .getPathParameters()
                .getFirst("slug");

        if (slug == null || slug.isBlank()) {
            return;
        }

        Post post = Post.find(
                        "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                        slug,
                        com.biliwind.blog.model.PostStatus.PUBLISHED)
                .firstResult();

        if (post == null) {
            return;
        }

        if (post.visibility != 2) {
            return;
        }

        jakarta.ws.rs.core.Cookie ticketCookie = ctx.getCookies().get("post_access_ticket_" + post.id);
        String ticketToken = ticketCookie == null ? null : ticketCookie.getValue();
        PostAccessPolicy.Decision access = postAccessPolicy.evaluate(
                post, resolveUserId(ctx), null, ticketToken,
                ctx.getHeaderString("X-Device-Id"));
        if (!access.allowed()) {
            abortWithPasswordPrompt(ctx, post);
        }
    }

    private Long resolveUserId(ContainerRequestContext ctx) {
        jakarta.ws.rs.core.Cookie cookie = ctx.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        UserTokenVerifier.VerifiedToken verified = userTokenVerifier.verify(cookie.getValue());
        return verified == null ? null : verified.uid();
    }

    private void abortWithPasswordPrompt(ContainerRequestContext ctx,
                                         Post post) {

        boolean isPjax =
                PjaxHelper.isPjaxRequest(ctx.getHeaders());

        TemplateInstance template =
                (isPjax ? passwordContentTemplate : passwordTemplate)
                        .data("post", post);

        Response response = Response
                .status(Response.Status.UNAUTHORIZED)
                .entity(template)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .header("Vary", "Cookie")
                .build();

        ctx.abortWith(response);
    }
}
