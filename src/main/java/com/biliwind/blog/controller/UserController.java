package com.biliwind.blog.controller;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 前台用户控制器，处理用户认证相关页面和API
 */
@Path("/user")
public class UserController {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    LanguageContext languageContext;

    @ConfigProperty(name = "user.jwt.secret", defaultValue = "windblog-user-dev-secret-change-me")
    String jwtSecret;

    @ConfigProperty(name = "user.jwt.issuer", defaultValue = "windblog-user")
    String issuer;

    @ConfigProperty(name = "user.jwt.expire-days", defaultValue = "7")
    long expireDays;

    @Inject
    @Location("user/login.html")
    Template loginTemplate;

    @Inject
    @Location("user/login.content.html")
    Template loginContentTemplate;

    @Inject
    ConfigManager configManager;

    @Inject
    @Location("user/register.html")
    Template registerTemplate;

    @Inject
    @Location("user/register.content.html")
    Template registerContentTemplate;

    @Inject
    @Location("user/center.html")
    Template centerTemplate;

    @Inject
    @Location("user/center.content.html")
    Template centerContentTemplate;

    @Inject
    com.biliwind.blog.service.WalletService walletService;

    @Inject
    com.biliwind.blog.service.CheckInService checkInService;

    // ==================== 页面路由 ====================

    /**
     * 登录页面
     */
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("redirect") String redirect, @Context HttpHeaders headers) {
        String targetRedirect = redirect != null ? redirect : "/";
        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        Template template = isPjax ? loginContentTemplate : loginTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("redirect", targetRedirect);
    }

    /**
     * 注册页面
     */
    @GET
    @Path("/register")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance registerPage(@QueryParam("redirect") String redirect, @Context HttpHeaders headers) {
        if (!configManager.getBoolean("feature_toggles", "enable_registration", true)) {
            throw new ForbiddenException("注册功能暂未开放");
        }
        String targetRedirect = redirect != null ? redirect : "/";
        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        Template template = isPjax ? registerContentTemplate : registerTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("redirect", targetRedirect);
    }

    @GET
    @Path("/center")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance centerPage(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return loginPage("/user/center", headers);
        }

        Long pointsBalance = walletService.getPointsBalance(profile.id());
        boolean hasCheckedIn = checkInService.hasCheckedInToday(profile.id());

        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        Template template = isPjax ? centerContentTemplate : centerTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("user", profile)
                .data("pointsBalance", pointsBalance)
                .data("hasCheckedIn", hasCheckedIn);
    }

    // ==================== API接口 ====================

    /**
     * 用户注册
     */
    @POST
    @Path("/api/register")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(
            @FormParam("username") @NotBlank @Size(min = 3, max = 20) String username,
            @FormParam("email") @NotBlank @Email String email,
            @FormParam("password") @NotBlank @Size(min = 6, max = 32) String password,
            @FormParam("redirect") String redirect) {

        if (!configManager.getBoolean("feature_toggles", "enable_registration", true)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "注册功能暂未开放"))
                    .build();
        }

        // 检查用户名是否已存在
        if (User.find("username = ?1 and deletedAt is null", username).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "用户名已被使用"))
                    .build();
        }

        // 检查邮箱是否已存在
        if (User.find("email = ?1 and deletedAt is null", email).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "邮箱已被注册"))
                    .build();
        }

        // 创建新用户
        User user = new User();
        user.username = username.trim();
        user.email = email.trim().toLowerCase();
        user.password = passwordHasher.hash(password);
        user.status = 1;
        user.roleName = RoleConstant.USER;
        user.persist();

        // 生成JWT Token
        String token = generateUserToken(user);

        // 设置Cookie
        NewCookie cookie = createAuthCookie(token);

        String targetUrl = redirect != null && !redirect.isBlank() ? redirect : "/";
        return Response.ok(Map.of(
                        "success", true,
                        "message", "注册成功",
                        "redirect", targetUrl,
                        "user", new UserProfile(user.id, user.username, user.email, user.roleName)
                ))
                .cookie(cookie)
                .build();
    }

    /**
     * 用户登录
     */
    @POST
    @Path("/api/login")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(
            @FormParam("account") @NotBlank String account,
            @FormParam("password") @NotBlank String password,
            @FormParam("remember") String remember,
            @FormParam("redirect") String redirect) {

        // 查找用户（支持用户名或邮箱登录）
        User user = User.find("(username = ?1 or email = ?1) and deletedAt is null",
                account.trim()).firstResult();

        if (user == null || user.status != 1) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "账号或密码错误"))
                    .build();
        }

        // 验证密码
        if (!passwordHasher.matches(password, user.password)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "账号或密码错误"))
                    .build();
        }

        // 生成JWT Token
        String token = generateUserToken(user);

        // 设置Cookie（记住我则延长有效期）
        boolean rememberMe = "on".equals(remember) || "true".equals(remember);
        NewCookie cookie = createAuthCookie(token, rememberMe);

        String targetUrl = redirect != null && !redirect.isBlank() ? redirect : "/";
        return Response.ok(Map.of(
                        "success", true,
                        "message", "登录成功",
                        "redirect", targetUrl,
                        "user", new UserProfile(user.id, user.username, user.email, user.roleName)
                ))
                .cookie(cookie)
                .build();
    }

    /**
     * 获取当前用户信息
     */
    @GET
    @Path("/api/profile")
    @Produces(MediaType.APPLICATION_JSON)
    public Response profile(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "未登录"))
                    .build();
        }
        return Response.ok(Map.of("success", true, "data", profile)).build();
    }

    /**
     * 用户登出
     */
    @POST
    @Path("/api/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout() {
        // 清除Cookie
        NewCookie clearCookie = new NewCookie.Builder("user_token")
                .value("")
                .path("/")
                .maxAge(0)
                .build();

        return Response.ok(Map.of("success", true, "message", "已登出"))
                .cookie(clearCookie)
                .build();
    }

    // ==================== 私有方法 ====================

    private String generateUserToken(User user) {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(Math.max(1, expireDays)));
        return Jwt.issuer(issuer)
                .upn(user.username)
                .groups(Set.of(RoleConstant.USER))
                .claim("uid", user.id)
                .claim("is_user", true)
                .claim("role_name", user.roleName != null ? user.roleName : RoleConstant.USER)
                .expiresAt(expiresAt)
                .signWithSecret(jwtSecret);
    }

    private NewCookie createAuthCookie(String token) {
        return createAuthCookie(token, false);
    }

    private NewCookie createAuthCookie(String token, boolean rememberMe) {
        long maxAge = rememberMe ? expireDays * 86400 : 86400; // 记住我则按配置，否则1天
        return new NewCookie.Builder("user_token")
                .value(token)
                .path("/")
                .maxAge((int) maxAge)
                .httpOnly(true)
                .secure(false) // 生产环境建议改为true
                .build();
    }

    private UserProfile resolveUserFromCookie(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }

        String token = cookie.getValue();
        try {
            // 简单解析JWT获取用户信息（实际应该使用TokenVerifier）
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            // 解析payload获取用户信息
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> claims = mapper.readValue(payload, new TypeReference<>() {
            });

            Long uid = claims.get("uid") instanceof Number n ? n.longValue() : null;
            String username = (String) claims.get("upn");
            String roleName = (String) claims.get("role_name");

            if (uid == null || username == null) {
                return null;
            }

            // 验证用户是否存在且有效
            User user = User.find("id = ?1 and deletedAt is null", uid).firstResult();
            if (user == null || user.status != 1) {
                return null;
            }

            return new UserProfile(user.id, user.username, user.email, user.roleName);
        } catch (Exception e) {
            return null;
        }
    }

    public record UserProfile(Long id, String username, String email, String roleName) {
    }
}