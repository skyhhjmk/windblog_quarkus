package com.biliwind.blog.controller;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserProfileReview;
import com.biliwind.blog.service.UserProfileReviewService;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.vertx.ext.web.RoutingContext;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.jwt.build.Jwt;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
    PageRenderer pageRenderer;

    @Inject
    SettingsReader settings;

    @Inject
    com.biliwind.blog.service.EmailVerificationService emailVerificationService;

    @Inject
    com.biliwind.blog.service.PasswordResetService passwordResetService;

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
    UserProfileReviewService profileReviewService;

    @Inject
    RoutingContext routingContext;

    // ==================== 页面路由 ====================

    /**
     * 登录页面
     */
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage(@QueryParam("redirect") String redirect, @Context HttpHeaders headers) {
        if (resolveUserFromCookie(headers) != null) {
            return redirectToUserCenter();
        }
        String targetRedirect = sanitizeRedirect(redirect);
        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        return Response.ok(pageRenderer.render(PageView.USER_LOGIN, headers, new PageRenderModel()
                        .put("pjaxRequest", isPjax)
                        .put("language", languageContext.getLang())
                        .put("redirect", targetRedirect)))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }

    /**
     * 注册页面
     */
    @GET
    @Path("/register")
    @Produces(MediaType.TEXT_HTML)
    public Response registerPage(@QueryParam("redirect") String redirect, @Context HttpHeaders headers) {
        if (resolveUserFromCookie(headers) != null) {
            return redirectToUserCenter();
        }
        if (!settings.flag(SettingKey.FEATURE_TOGGLES, "enable_registration")) {
            throw new ForbiddenException("注册功能暂未开放");
        }
        String targetRedirect = sanitizeRedirect(redirect);
        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        return Response.ok(pageRenderer.render(PageView.USER_REGISTER, headers, new PageRenderModel()
                        .put("pjaxRequest", isPjax)
                        .put("language", languageContext.getLang())
                        .put("redirect", targetRedirect)))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }

    @GET
    @Path("/forgot-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgotPasswordPage(@Context HttpHeaders headers) {
        return pageRenderer.render(PageView.USER_FORGOT_PASSWORD, headers, new PageRenderModel()
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(headers))
                .put("language", languageContext.getLang()));
    }

    @GET
    @Path("/reset-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resetPasswordPage(@QueryParam("token") String token,
                                               @Context HttpHeaders headers) {
        return pageRenderer.render(PageView.USER_RESET_PASSWORD, headers, new PageRenderModel()
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(headers))
                .put("language", languageContext.getLang())
                .put("token", token == null ? "" : token)
                .put("valid", passwordResetService.isTokenValid(token)));
    }

    @GET
    @Path("/center")
    @Produces(MediaType.TEXT_HTML)
    public Response centerPage(@Context HttpHeaders headers) {
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

        // 计算当前等级的经验上限
        int nextLevelRequiredExp = (level) * (level + 1) / 2 * 100;
        int currentLevelBaseExp = (level - 1) * level / 2 * 100;
        int currentLevelExp = Math.max(0, exp - currentLevelBaseExp);
        int currentLevelMaxExp = nextLevelRequiredExp - currentLevelBaseExp;
        int expPercent = Math.min(100, Math.max(0, (int) ((float) currentLevelExp / currentLevelMaxExp * 100)));

        boolean isPjax = PjaxHelper.isPjaxRequest(headers);
        UserProfileReview latestReview = UserProfileReview
                .find("userId = ?1 order by id desc", profile.id())
                .firstResult();
        return Response.ok(pageRenderer.render(PageView.USER_CENTER, headers, new PageRenderModel()
                        .put("pjaxRequest", isPjax)
                        .put("language", languageContext.getLang())
                        .put("user", profile)
                        .put("emailVerified", profile.emailVerified())
                        .put("pendingReview", latestReview)
                        .put("pointsBalance", pointsBalance)
                        .put("hasCheckedIn", hasCheckedIn)
                        .put("level", level)
                        .put("exp", exp)
                        .put("currentLevelExp", currentLevelExp)
                        .put("currentLevelMaxExp", currentLevelMaxExp)
                        .put("expToNextLevel", Math.max(0, currentLevelMaxExp - currentLevelExp))
                        .put("expPercent", expPercent)
                        .put("allowUserPostSubmission",
                                settings.flag(SettingKey.FEATURE_TOGGLES, "enable_user_post_submission"))))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
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

        if (!settings.flag(SettingKey.FEATURE_TOGGLES, "enable_registration")) {
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

        // 用户名与邮箱分开提示会形成账号枚举接口；两者统一返回同一条信息。
        boolean usernameTaken = User.find("username = ?1 and deletedAt is null", normalizedUsername).firstResult() != null;
        boolean emailTaken = User.find("email = ?1 and deletedAt is null", normalizedEmail).firstResult() != null;
        if (usernameTaken || emailTaken) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "用户名或邮箱已被使用，请更换后重试"))
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
                        "user", UserProfile.from(user)
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
        // Keep the account bucket per source address so a third party cannot
        // lock a real user out by repeatedly failing the same username.
        String accountLimitKey = "user-login-account:" + normalizedAccount + ":ip:" + clientIp;
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
                        "user", UserProfile.from(user)
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

    @GET
    @Path("/api/profile/review")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProfileReviewStatus(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "登录已过期，请重新登录"))
                    .build();
        }
        UserProfileReview latest = UserProfileReview
                .find("userId = ?1 order by id desc", profile.id())
                .firstResult();
        if (latest == null) {
            return Response.ok(Map.of("success", true, "data", Map.of())).build();
        }
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("id", latest.id);
        item.put("status", latest.status);
        item.put("newNickname", latest.newNickname);
        item.put("newBio", latest.newBio);
        item.put("hasPendingAvatar", latest.pendingAvatarPath != null && !latest.pendingAvatarPath.isBlank());
        item.put("aiStatus", latest.aiStatus);
        item.put("aiReason", latest.aiReason);
        item.put("reviewNote", latest.reviewNote);
        item.put("updatedAt", latest.updatedAt);
        return Response.ok(Map.of("success", true, "data", item)).build();
    }

    @POST
    @Path("/api/profile/review")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitProfileReview(
            @org.jboss.resteasy.reactive.RestForm("nickname") String nickname,
            @org.jboss.resteasy.reactive.RestForm("bio") String bio,
            @org.jboss.resteasy.reactive.RestForm("avatar") org.jboss.resteasy.reactive.multipart.FileUpload avatar,
            @Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "登录已过期，请重新登录"))
                    .build();
        }
        byte[] avatarBytes = null;
        if (avatar != null && avatar.filePath() != null) {
            if (avatar.size() > UserProfileReviewService.MAX_AVATAR_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("success", false, "message", "头像文件过大，不能超过 2 MiB"))
                        .build();
            }
            try {
                avatarBytes = java.nio.file.Files.readAllBytes(avatar.filePath());
            } catch (java.io.IOException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("success", false, "message", "读取头像文件失败"))
                        .build();
            }
            if (avatarBytes.length > UserProfileReviewService.MAX_AVATAR_BYTES) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("success", false, "message", "头像文件过大，不能超过 2 MiB"))
                        .build();
            }
        }
        try {
            UserProfileReview review = profileReviewService.submitChange(profile.id(), nickname, bio, avatarBytes);
            String message = "APPROVED".equals(review.status)
                    ? "资料修改已通过审核并生效"
                    : "REJECTED".equals(review.status)
                    ? ("资料修改未通过审核：" + (review.reviewNote == null ? "" : review.reviewNote))
                    : "资料修改已提交，正在等待审核";
            return Response.ok(Map.of(
                    "success", true,
                    "message", message,
                    "status", review.status,
                    "aiStatus", review.aiStatus,
                    "reviewId", review.id
            )).build();
        } catch (BadRequestException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", ex.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/api/profile/pending-avatar")
    @Produces(MediaType.WILDCARD)
    public Response getPendingAvatar(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            throw new NotAuthorizedException("请先登录");
        }
        UserProfileReviewService.AvatarPayload payload = profileReviewService.loadPendingAvatarForUser(profile.id());
        if (payload == null) {
            throw new NotFoundException();
        }
        return Response.ok(payload.bytes(), payload.mimeType())
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("/avatars/{fileName}")
    @Produces(MediaType.WILDCARD)
    public Response getPublicAvatar(@PathParam("fileName") String fileName) {
        UserProfileReviewService.AvatarPayload payload = profileReviewService.loadPublicAvatar(fileName);
        if (payload == null) {
            throw new NotFoundException();
        }
        return Response.ok(payload.bytes(), payload.mimeType())
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    @POST
    @Path("/api/account/email")
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changeEmail(ChangeEmailRequest request, @Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "登录已过期，请重新登录"))
                    .build();
        }
        if (request == null || request.currentPassword() == null || request.currentPassword().isBlank()
                || request.newEmail() == null || request.newEmail().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "请输入当前密码和新邮箱"))
                    .build();
        }

        User user = User.findById(profile.id());
        if (user == null || !passwordHasher.matches(request.currentPassword(), user.password)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "当前密码错误"))
                    .build();
        }

        String normalizedEmail = request.newEmail().trim().toLowerCase(Locale.ROOT);
        if (!normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "邮箱格式不正确"))
                    .build();
        }
        if (normalizedEmail.equalsIgnoreCase(user.email)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "新邮箱不能与当前邮箱相同"))
                    .build();
        }
        User existing = User.find("email = ?1 and id <> ?2 and deletedAt is null", normalizedEmail, user.id)
                .firstResult();
        if (existing != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "新邮箱已被其他账号使用"))
                    .build();
        }

        user.email = normalizedEmail;
        user.emailVerifiedAt = null;
        emailVerificationService.sendVerification(user);
        return Response.ok(Map.of(
                "success", true,
                "message", "邮箱已更新，请查收新邮箱中的验证邮件",
                "email", normalizedEmail
        )).build();
    }

    @POST
    @Path("/api/account/password")
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changePassword(ChangePasswordRequest request, @Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "登录已过期，请重新登录"))
                    .build();
        }
        if (request == null || request.currentPassword() == null || request.currentPassword().isBlank()
                || request.newPassword() == null || request.newPassword().isBlank()
                || !Objects.equals(request.newPassword(), request.confirmPassword())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "请完整填写密码并确认两次输入一致"))
                    .build();
        }
        if (request.newPassword().length() < 6 || request.newPassword().length() > 32) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "新密码长度应为 6-32 位"))
                    .build();
        }

        User user = User.findById(profile.id());
        if (user == null || !passwordHasher.matches(request.currentPassword(), user.password)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "当前密码错误"))
                    .build();
        }
        if (passwordHasher.matches(request.newPassword(), user.password)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "新密码不能与当前密码相同"))
                    .build();
        }

        user.password = passwordHasher.hash(request.newPassword());
        // 密码变更后所有已签发的 token 立即失效。
        user.tokenVersion = user.tokenVersion + 1;
        return Response.ok(Map.of("success", true, "message", "密码修改成功")).build();
    }

    /**
     * 用户登出
     */
    @POST
    @Path("/api/logout")
    @Transactional
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout(@Context HttpHeaders headers) {
        UserProfile profile = resolveUserFromCookie(headers);
        if (profile != null) {
            User user = User.findById(profile.id());
            if (user != null) {
                // 递增代次，使该账号此前签发的所有 token（含可能被盗的 token）失效。
                user.tokenVersion = user.tokenVersion + 1;
            }
        }

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
                .claim("ver", user.tokenVersion)
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

    private Response redirectToUserCenter() {
        return Response.seeOther(UriBuilder.fromPath("/user/center").build()).build();
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

        return UserProfile.from(user);
    }

    @RegisterForReflection
    public record UserProfile(
            Long id,
            String username,
            String email,
            String roleName,
            String nickname,
            String avatar,
            String bio,
            boolean emailVerified
    ) {
        public static UserProfile from(User user) {
            return new UserProfile(
                    user.id,
                    user.username,
                    user.email,
                    user.roleName,
                    user.nickname,
                    user.avatar,
                    user.bio,
                    user.emailVerifiedAt != null
            );
        }

        public String displayName() {
            return (nickname != null && !nickname.isBlank()) ? nickname : username;
        }
    }

    public record SubscriptionRequest(boolean subscribeArticleUpdates, boolean subscribePromotions) {
    }

    public record ChangeEmailRequest(String currentPassword, String newEmail) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword, String confirmPassword) {
    }
}
