package com.biliwind.blog.controller;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ConfigManager;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.vertx.ext.web.RoutingContext;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 前台用户控制器，处理用户认证相关页面和API
 */
@Path("/user")
public class UserController {

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @Inject
    LanguageContext languageContext;

    @ConfigProperty(name = "user.jwt.secret", defaultValue = "windblog-user-dev-secret-change-me")
    String jwtSecret;

    @ConfigProperty(name = "user.jwt.issuer", defaultValue = "windblog-user")
    String issuer;

    @ConfigProperty(name = "user.jwt.expire-days", defaultValue = "7")
    long expireDays;

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @Inject
    @Location("user/login.html")
    Template loginTemplate;

    @Inject
    @Location("user/login.content.html")
    Template loginContentTemplate;

    @Inject
    ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.EmailVerificationService emailVerificationService;

    @Inject
    com.biliwind.blog.service.PasswordResetService passwordResetService;

    @Inject
    @Location("user/register.html")
    Template registerTemplate;

    @Inject
    @Location("user/register.content.html")
    Template registerContentTemplate;

    @Inject
    @Location("user/forgot-password.html")
    Template forgotPasswordTemplate;

    @Inject
    @Location("user/forgot-password.content.html")
    Template forgotPasswordContentTemplate;

    @Inject
    @Location("user/reset-password.html")
    Template resetPasswordTemplate;

    @Inject
    @Location("user/reset-password.content.html")
    Template resetPasswordContentTemplate;

    @Inject
    @Location("user/center.html")
    Template centerTemplate;

    @Inject
    @Location("user/center.content.html")
    Template centerContentTemplate;

    @Inject
    com.biliwind.blog.service.WalletService walletService;

    @Inject
    jakarta.enterprise.event.Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @Inject
    com.biliwind.blog.service.CheckInService checkInService;

    @Inject
    com.biliwind.blog.service.StoreService storeService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    SecurityRateLimitService securityRateLimitService;

    @Inject
    RoutingContext routingContext;

    // ==================== 页面路由 ====================

    /**
     * 登录页面
     */
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("redirect") String redirect, @Context HttpHeaders headers) {
        String targetRedirect = sanitizeRedirect(redirect);
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
        String targetRedirect = sanitizeRedirect(redirect);
        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        Template template = isPjax ? registerContentTemplate : registerTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("redirect", targetRedirect);
    }

    @GET
    @Path("/forgot-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgotPasswordPage(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers)
                ? forgotPasswordContentTemplate
                : forgotPasswordTemplate;
        return template.data("language", languageContext.getLang());
    }

    @GET
    @Path("/reset-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resetPasswordPage(@QueryParam("token") String token,
                                               @Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers)
                ? resetPasswordContentTemplate
                : resetPasswordTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("token", token == null ? "" : token)
                .data("valid", passwordResetService.isTokenValid(token));
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

        // 获取用户完整信息以获取 gamification 数据
        User fullUser = User.findById(profile.id());
        int level = fullUser != null ? fullUser.level : 1;
        int exp = fullUser != null ? fullUser.exp : 0;
        int backpackCapacity = fullUser != null ? fullUser.backpackCapacity : 36;

        // 计算当前等级的经验上限
        int nextLevelRequiredExp = (level) * (level + 1) / 2 * 100;
        int currentLevelBaseExp = (level - 1) * level / 2 * 100;
        int currentLevelExp = exp - currentLevelBaseExp;
        int currentLevelMaxExp = nextLevelRequiredExp - currentLevelBaseExp;
        int expPercent = Math.min(100, Math.max(0, (int) ((float) currentLevelExp / currentLevelMaxExp * 100)));

        List<com.biliwind.blog.model.UserBackpackItem> backpack = storeService.getUserBackpack(profile.id());
        List<Long> storeItemIds = new ArrayList<>();
        for (com.biliwind.blog.model.UserBackpackItem item : backpack) {
            if (item.storeItemId != null && !storeItemIds.contains(item.storeItemId)) {
                storeItemIds.add(item.storeItemId);
            }
        }

        Map<Long, com.biliwind.blog.model.StoreItem> storeItemsById = new HashMap<>();
        if (!storeItemIds.isEmpty()) {
            List<com.biliwind.blog.model.StoreItem> storeItems =
                    com.biliwind.blog.model.StoreItem.find("id in ?1", storeItemIds).list();
            for (com.biliwind.blog.model.StoreItem storeItem : storeItems) {
                storeItemsById.put(storeItem.id, storeItem);
            }
        }

        List<Map<String, Object>> backpackDetails = new ArrayList<>();
        for (com.biliwind.blog.model.UserBackpackItem item : backpack) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", item.id);
            map.put("storeItemId", item.storeItemId);
            com.biliwind.blog.model.StoreItem storeItem = storeItemsById.get(item.storeItemId);
            if (storeItem != null) {
                map.put("name", storeItem.name);
                String rarity = storeItem.rarity;
                if (rarity == null || rarity.isBlank()) {
                    rarity = "#4b5563";
                }
                map.put("rarity", rarity);
                map.put("type", storeItem.type);
                map.put("description", storeItem.description);
            } else {
                map.put("name", "未知物品");
                map.put("rarity", "#4b5563");
            }
            backpackDetails.add(map);
        }

        // 填充空白格子以满足容量
        while (backpackDetails.size() < backpackCapacity) {
            backpackDetails.add(null);
        }

        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        Template template = isPjax ? centerContentTemplate : centerTemplate;
        return template
                .data("language", languageContext.getLang())
                .data("user", profile)
                .data("pointsBalance", pointsBalance)
                .data("hasCheckedIn", hasCheckedIn)
                .data("level", level)
                .data("exp", exp)
                .data("currentLevelExp", currentLevelExp)
                .data("currentLevelMaxExp", currentLevelMaxExp)
                .data("expPercent", expPercent)
                .data("backpackCapacity", backpackCapacity)
                .data("backpackItems", backpackDetails);
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
            @FormParam("confirmPassword") String confirmPassword,
            @FormParam("agreement") String agreement,
            @FormParam("subscribeArticleUpdates") String subscribeArticleUpdates,
            @FormParam("subscribePromotions") String subscribePromotions,
            @FormParam("redirect") String redirect) {

        if (!configManager.getBoolean("feature_toggles", "enable_registration", true)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "注册功能暂未开放"))
                    .build();
        }

        if (!"on".equalsIgnoreCase(agreement) && !"true".equalsIgnoreCase(agreement)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "请先同意用户协议和隐私政策"))
                    .build();
        }
        if (confirmPassword == null || !Objects.equals(password, confirmPassword)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "两次输入的密码不一致"))
                    .build();
        }

        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        if (!securityRateLimitService.tryAcquire("user-register-ip:" + clientIp, 5, Duration.ofHours(1))) {
            return rateLimitedResponse("注册尝试过于频繁，请一小时后重试", 3600);
        }

        // 检查用户名是否已存在
        if (User.find("username = ?1 and deletedAt is null", normalizedUsername).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "用户名已被使用"))
                    .build();
        }

        // 检查邮箱是否已存在
        if (User.find("email = ?1 and deletedAt is null", normalizedEmail).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "邮箱已被注册"))
                    .build();
        }

        // 创建新用户
        User user = new User();
        user.username = normalizedUsername;
        user.email = normalizedEmail;
        user.password = passwordHasher.hash(password);
        user.status = 1;
        user.roleName = RoleConstant.USER;
        user.subscribeArticleUpdates = "on".equals(subscribeArticleUpdates);
        user.subscribePromotions = "on".equals(subscribePromotions);
        user.persist();
        emailVerificationService.sendVerification(user);
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("USER", user.id, "UPSERT"));

        // 生成JWT Token
        String token = generateUserToken(user);

        // 设置Cookie
        NewCookie cookie = createAuthCookie(token);

        String targetUrl = sanitizeRedirect(redirect);
        return Response.ok(Map.of(
                        "success", true,
                        "message", "注册成功，请查收验证邮件",
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

        String normalizedAccount = account.trim().toLowerCase(Locale.ROOT);
        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        String accountLimitKey = "user-login-account:" + normalizedAccount;
        String ipLimitKey = "user-login-ip:" + clientIp;
        Duration loginLimitWindow = Duration.ofMinutes(15);
        if (!securityRateLimitService.tryAcquire(accountLimitKey, 10, loginLimitWindow)
                || !securityRateLimitService.tryAcquire(ipLimitKey, 20, loginLimitWindow)) {
            return rateLimitedResponse("登录尝试过于频繁，请15分钟后重试", 900);
        }

        // 查找用户（支持用户名或邮箱登录）
        User user = User.find("(username = ?1 or email = ?2) and deletedAt is null",
                account.trim(), normalizedAccount).firstResult();

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

        securityRateLimitService.clear(accountLimitKey);
        securityRateLimitService.clear(ipLimitKey);

        // 生成JWT Token
        String token = generateUserToken(user);

        // 设置Cookie（记住我则延长有效期）
        boolean rememberMe = "on".equals(remember) || "true".equals(remember);
        NewCookie cookie = createAuthCookie(token, rememberMe);

        String targetUrl = sanitizeRedirect(redirect);
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
                .secure(cookieSecure)
                .sameSite(NewCookie.SameSite.LAX)
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
                .secure(cookieSecure)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
    }

    @POST
    @Path("/api/forgot-password")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestPasswordReset(@FormParam("email") String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        if (!securityRateLimitService.tryAcquire(
                "user-password-reset-ip:" + clientIp, 5, Duration.ofHours(1))) {
            return rateLimitedResponse("操作过于频繁，请一小时后重试", 3600);
        }
        if (!normalizedEmail.isBlank()
                && !securityRateLimitService.tryAcquire(
                "user-password-reset-email:" + normalizedEmail, 3, Duration.ofHours(1))) {
            return rateLimitedResponse("操作过于频繁，请一小时后重试", 3600);
        }

        passwordResetService.requestReset(normalizedEmail);
        return Response.ok(Map.of(
                "success", true,
                "message", "如果该邮箱已注册，重置链接会发送到邮箱。请检查收件箱和垃圾邮件。"
        )).build();
    }

    @POST
    @Path("/api/reset-password")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetPassword(
            @FormParam("token") String token,
            @FormParam("password") @NotBlank @Size(min = 6, max = 32) String password,
            @FormParam("confirmPassword") String confirmPassword) {
        if (!Objects.equals(password, confirmPassword)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "两次输入的密码不一致"))
                    .build();
        }
        boolean reset = passwordResetService.resetPassword(token, password);
        if (!reset) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "重置链接无效或已过期，请重新申请"))
                    .build();
        }
        return Response.ok(Map.of(
                "success", true,
                "message", "密码已重置，请使用新密码登录",
                "redirect", "/user/login"
        )).build();
    }

    @POST
    @Path("/api/resend-verification")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resendVerification(@FormParam("email") String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        if (!securityRateLimitService.tryAcquire(
                "user-verification-resend-ip:" + clientIp, 5, Duration.ofHours(1))) {
            return rateLimitedResponse("操作过于频繁，请一小时后重试", 3600);
        }
        User user = normalizedEmail.isBlank()
                ? null
                : User.find("email = ?1 and deletedAt is null", normalizedEmail).firstResult();
        if (user != null && user.status == 1 && user.emailVerifiedAt == null) {
            emailVerificationService.sendVerification(user);
        }
        return Response.ok(Map.of(
                "success", true,
                "message", "如果该邮箱需要验证，验证邮件会发送到邮箱。请检查收件箱和垃圾邮件。"
        )).build();
    }

    private Response rateLimitedResponse(String message, int retryAfterSeconds) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .entity(Map.of("success", false, "message", message))
                .build();
    }

    @POST
    @Path("/api/subscriptions")
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateSubscriptions(SubscriptionRequest request, @Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) throw new NotAuthorizedException("请先登录");
        User user = User.findById(profile.id());
        if (user.emailVerifiedAt == null) throw new ForbiddenException("请先完成邮箱验证后再管理订阅");
        user.subscribeArticleUpdates = request.subscribeArticleUpdates();
        user.subscribePromotions = request.subscribePromotions();
        return Response.ok(Map.of("success", true)).build();
    }

    @GET
    @Path("/api/subscriptions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSubscriptions(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            throw new NotAuthorizedException("请先登录");
        }
        User user = User.findById(profile.id());
        return Response.ok(Map.of("success", true, "data", Map.of(
                "emailVerified", user.emailVerifiedAt != null,
                "subscribeArticleUpdates", user.subscribeArticleUpdates,
                "subscribePromotions", user.subscribePromotions))).build();
    }

    @GET
    @Path("/verify-email")
    @Produces(MediaType.TEXT_HTML)
    public Response verifyEmail(@QueryParam("token") String token) {
        boolean verified = token != null && emailVerificationService.verify(token);
        String message = verified ? "邮箱验证成功，您现在可以发表评论和管理订阅。" : "验证链接无效或已过期。";
        return Response.ok("<html><body><h1>" + message + "</h1><p><a href=\"/user/login\">返回登录</a></p></body></html>").build();
    }

    private String sanitizeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return "/";
        }

        String trimmedRedirect = redirect.trim();
        if (!trimmedRedirect.startsWith("/")) {
            return "/";
        }
        if (trimmedRedirect.startsWith("//")) {
            return "/";
        }
        if (trimmedRedirect.contains("\\")) {
            return "/";
        }

        return trimmedRedirect;
    }

    private UserProfile resolveUserFromCookie(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }

        String token = cookie.getValue();
        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(token);
        if (verified == null) {
            return null;
        }

        // 验证用户是否存在且有效
        User user = User.find("id = ?1 and deletedAt is null", verified.uid()).firstResult();
        if (user == null || user.status != 1) {
            return null;
        }

        return new UserProfile(user.id, user.username, user.email, user.roleName);
    }

    public record UserProfile(Long id, String username, String email, String roleName) {
    }

    public record SubscriptionRequest(boolean subscribeArticleUpdates, boolean subscribePromotions) {
    }
}
