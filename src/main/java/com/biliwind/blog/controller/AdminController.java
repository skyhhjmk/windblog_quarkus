package com.biliwind.blog.controller;

import com.biliwind.blog.controller.api.admin.AdminTokenVerifier;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理后台页面控制器
 * 负责渲染管理后台的各个页面
 */
@Path("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Inject
    AdminTokenVerifier tokenVerifier;

    @Inject
    @Location("admin/dashboard.html")
    Template dashboard;

    @Inject
    @Location("admin/posts.html")
    Template posts;

    @Inject
    @Location("admin/categories.html")
    Template categories;

    @Inject
    @Location("admin/tags.html")
    Template tags;

    @Inject
    @Location("admin/comments.html")
    Template comments;

    @Inject
    @Location("admin/users.html")
    Template users;

    @Inject
    @Location("admin/media.html")
    Template media;

    @Inject
    @Location("admin/queues.html")
    Template queues;

    @Inject
    @Location("admin/permissions.html")
    Template permissions;

    @Inject
    @Location("admin/ai-providers.html")
    Template aiProviders;

    @Inject
    @Location("admin/database.html")
    Template database;

    @Inject
    @Location("admin/wallet.html")
    Template wallet;

    @Inject
    @Location("admin/login.html")
    Template login;

    /**
     * 登录页面
     */
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login() {
        return login
                .data("title", "登录 - WindBlog 管理后台")
                .data("activePage", "login");
    }

    /**
     * 管理后台首页（概览）
     */
    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard(@Context HttpHeaders httpHeaders) {
        // 验证登录状态
        var userInfo = verifyLogin(httpHeaders);
        
        return dashboard
                .data("title", "概览 - WindBlog 管理后台")
                .data("activePage", "dashboard")
                .data("pageTitle", "概览")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 文章管理页面
     */
    @GET
    @Path("/posts")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance posts(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return posts
                .data("title", "文章管理 - WindBlog 管理后台")
                .data("activePage", "posts")
                .data("pageTitle", "文章管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 分类管理页面
     */
    @GET
    @Path("/categories")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance categories(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return categories
                .data("title", "分类管理 - WindBlog 管理后台")
                .data("activePage", "categories")
                .data("pageTitle", "分类管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 标签管理页面
     */
    @GET
    @Path("/tags")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance tags(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return tags
                .data("title", "标签管理 - WindBlog 管理后台")
                .data("activePage", "tags")
                .data("pageTitle", "标签管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 评论管理页面
     */
    @GET
    @Path("/comments")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance comments(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return comments
                .data("title", "评论管理 - WindBlog 管理后台")
                .data("activePage", "comments")
                .data("pageTitle", "评论管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 用户管理页面
     */
    @GET
    @Path("/users")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance users(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return users
                .data("title", "用户管理 - WindBlog 管理后台")
                .data("activePage", "users")
                .data("pageTitle", "用户管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 媒体库页面
     */
    @GET
    @Path("/media")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance media(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);
        
        return media
                .data("title", "媒体库 - WindBlog 管理后台")
                .data("activePage", "media")
                .data("pageTitle", "媒体库")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 队列管理页面
     */
    @GET
    @Path("/queues")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance queues(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);

        return queues
                .data("title", "队列管理 - WindBlog 管理后台")
                .data("activePage", "queues")
                .data("pageTitle", "队列管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 权限管理页面
     */
    @GET
    @Path("/permissions")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance permissions(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);

        return permissions
                .data("title", "权限管理 - WindBlog 管理后台")
                .data("activePage", "permissions")
                .data("pageTitle", "权限管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * AI 配置页面
     */
    @GET
    @Path("/ai-providers")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance aiProviders(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);

        return aiProviders
                .data("title", "AI 配置 - WindBlog 管理后台")
                .data("activePage", "ai-providers")
                .data("pageTitle", "AI 配置")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 数据库管理页面
     */
    @GET
    @Path("/database")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance database(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);

        return database
                .data("title", "数据库管理 - WindBlog 管理后台")
                .data("activePage", "database")
                .data("pageTitle", "数据库管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 钱包管理页面
     */
    @GET
    @Path("/wallet")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance wallet(@Context HttpHeaders httpHeaders) {
        var userInfo = verifyLogin(httpHeaders);

        return wallet
                .data("title", "钱包管理 - WindBlog 管理后台")
                .data("activePage", "wallet")
                .data("pageTitle", "钱包管理")
                .data("userName", userInfo.username())
                .data("userAvatar", userInfo.avatar());
    }

    /**
     * 验证登录状态
     * @return 用户信息（用户名和头像）
     */
    private UserInfo verifyLogin(HttpHeaders httpHeaders) {
        try {
            // 优先从 Authorization header 读取 token
            String token = httpHeaders.getHeaderString("Authorization");
            
            // 如果 header 中没有，尝试从 cookie 读取
            if (token == null || token.isBlank()) {
                jakarta.ws.rs.core.Cookie cookie = httpHeaders.getCookies().get("admin_token");
                if (cookie != null) {
                    token = cookie.getValue();
                }
            }
            
            if (token == null || token.isBlank()) {
                throw new jakarta.ws.rs.WebApplicationException("未授权");
            }
            
            var userProfile = tokenVerifier.verifyTokenAndGetProfile(token);
            
            if (userProfile == null) {
                throw new jakarta.ws.rs.WebApplicationException("未授权");
            }
            
            String username = userProfile.username();
            String avatar = username.substring(0, 1).toUpperCase();
            
            return new UserInfo(username, avatar);
        } catch (Exception e) {
            log.debug("验证 token 失败，重定向到登录页", e);
            throw new jakarta.ws.rs.WebApplicationException(
                jakarta.ws.rs.core.Response
                    .status(302)
                    .location(jakarta.ws.rs.core.UriBuilder.fromPath("/admin/login").build())
                    .build()
            );
        }
    }

    /**
     * 用户信息记录
     */
    private record UserInfo(String username, String avatar) {
    }
}
