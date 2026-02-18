package com.biliwind.blog.filter;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.model.Post;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
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

    private static final String PASSWORD_HEADER = "X-Post-Password";

    @Inject
    @Location("blog/password.html")
    Template passwordTemplate;

    @Inject
    @Location("blog/password.content.html")
    Template passwordContentTemplate;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {

        String slug = ctx.getUriInfo()
                .getPathParameters()
                .getFirst("slug");

        if (slug == null || slug.isBlank()) {
            return;
        }

        Post post = Post.find("slug = ?1 and deletedAt is null", slug)
                .firstResult();

        if (post == null) {
            return;
        }

        if (post.visibility != 2) {
            return;
        }

        String submittedPassword =
                resolveSubmittedPassword(post.id, ctx);

        if (!isPasswordValid(post, submittedPassword)) {
            abortWithPasswordPrompt(ctx, post);
        }
    }

    private String resolveSubmittedPassword(Long postId,
                                            ContainerRequestContext ctx) {

        String headerPassword =
                ctx.getHeaderString(PASSWORD_HEADER);

        if (headerPassword != null && !headerPassword.isBlank()) {
            return headerPassword;
        }

        Cookie cookie =
                ctx.getCookies().get("post_pw_" + postId);

        if (cookie != null) {
            return cookie.getValue();
        }

        return null;
    }

    private boolean isPasswordValid(Post post,
                                    String submittedPassword) {

        if (submittedPassword == null) {
            return false;
        }

        return submittedPassword.equals(post.password);
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
                .build();

        ctx.abortWith(response);
    }
}
