package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.UserCheckIn;
import com.biliwind.blog.model.UserWallet;
import com.biliwind.blog.model.WalletTransaction;
import com.biliwind.blog.service.CheckInService;
import com.biliwind.blog.service.WalletService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.ProofOfWorkService;
import com.biliwind.blog.service.security.ProofOfWorkService.PowScene;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 用户中心钱包与签到 API
 */
@Path("/api/user/wallet")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserWallet")
public class UserWalletApiController {

    @Inject
    WalletService walletService;

    @Inject
    CheckInService checkInService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @Inject
    ProofOfWorkService proofOfWorkService;

    @Inject
    SecurityRateLimitService securityRateLimitService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    /**
     * 获取钱包基本信息与今日签到状态
     */
    @GET
    @Path("/info")
    @Operation(summary = "获取用户钱包信息")
    public Response getWalletInfo(@Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
        }

        UserWallet wallet = walletService.getWalletByUserId(userId);
        if (wallet == null) {
            wallet = walletService.createWallet(userId);
        }

        boolean checkedInToday = checkInService.hasCheckedInToday(userId);

        return Response.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "userId", userId,
                        "pointsBalance", wallet.pointsBalance,
                        "checkedInToday", checkedInToday
                )
        )).build();
    }

    /**
     * 获取每日签到工作量证明挑战
     */
    @GET
    @Path("/check-in/challenge")
    @Operation(summary = "获取每日签到 PoW 挑战")
    public Response checkInChallenge(@Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
        }

        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        if (!securityRateLimitService.tryAcquire("wallet-checkin-pow-challenge:" + clientIp, 30, Duration.ofMinutes(1))) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", 60)
                    .entity(Map.of("success", false, "message", "安全校验请求过于频繁，请稍后重试"))
                    .build();
        }

        ProofOfWorkService.Challenge challenge = proofOfWorkService.issueForRequest(PowScene.CHECK_IN, routingContext);
        return Response.ok(Map.of("success", true, "data", challenge))
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * 每日签到
     */
    @POST
    @Path("/check-in")
    @Operation(summary = "每日签到")
    public Response checkIn(@Context HttpHeaders headers, CheckInRequest request) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
        }

        try {
            String challengeId = request == null ? null : request.challengeId();
            String powNonce = request == null ? null : request.powNonce();
            proofOfWorkService.verifyForRequest(challengeId, powNonce, PowScene.CHECK_IN, routingContext);
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false,
                            "message", SensitiveMessageSanitizer.sanitize(e.getMessage())))
                    .build();
        }

        try {
            UserCheckIn record = checkInService.doCheckIn(userId);
            UserWallet wallet = walletService.getWalletByUserId(userId);
            return Response.ok(Map.of(
                    "success", true,
                    "message", "签到成功",
                    "newBalance", wallet != null ? wallet.pointsBalance : 0,
                    "data", record
            )).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false,
                            "message", SensitiveMessageSanitizer.sanitize(e.getMessage())))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "message", "系统繁忙，请稍后重试"))
                    .build();
        }
    }

    @RegisterForReflection
    public record CheckInRequest(String challengeId, String powNonce) {
    }

    /**
     * 获取积分交易记录
     */
    @GET
    @Path("/transactions")
    @Operation(summary = "获取积分历史")
    public Response getTransactions(
            @Context HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
        }

        List<WalletTransaction> list = walletService.getTransactionHistory(userId, page, pageSize);
        long total = walletService.getTransactionCount(userId);

        return Response.ok(Map.of(
                "success", true,
                "data", list,
                "total", total,
                "page", page,
                "pageSize", pageSize
        )).build();
    }

    // ==================== 私有辅助方法 ====================

    private Long resolveUserId(HttpHeaders headers) {
        jakarta.ws.rs.core.Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }

        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        if (verified == null) {
            return null;
        }
        return verified.uid();
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("success", false, "message", "未登录或登录已过期"))
                .build();
    }
}
