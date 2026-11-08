package com.biliwind.blog.controller;

import com.biliwind.blog.service.CommentCaptchaService;
import com.biliwind.blog.service.ReactionService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.Map;

@Path("/api/reactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReactionApiController {

    /**
     * Lightweight difficulty (12 leading zero bits, ~4096 hashes on average)
     * Solves in 10-20ms in client worker, providing frictionless UX for humans
     * while imposing a massive computational barrier against automated spam floods.
     */
    public static final int REACTION_POW_DIFFICULTY = 12;

    @Inject
    CommentCaptchaService captchaService;

    @Inject
    ReactionService reactionService;

    @Inject
    SecurityRateLimitService rateLimitService;

    @Inject
    ClientIpResolver clientIpResolver;

    private void checkRate(io.vertx.ext.web.RoutingContext context, String action, int limit) {
        String ip = clientIpResolver.resolve(context).clientIp();
        if (!rateLimitService.tryAcquire("reaction-" + action + ":" + ip, limit, Duration.ofMinutes(1))) {
            throw new WebApplicationException("操作过于频繁，请稍后重试", 429);
        }
    }

    @GET
    @Path("/challenge")
    public Response challenge(@Context io.vertx.ext.web.RoutingContext context) {
        checkRate(context, "challenge", 60);
        CommentCaptchaService.Challenge challenge = captchaService.issue(REACTION_POW_DIFFICULTY);
        return Response.ok(Map.of("success", true, "data", challenge))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/{slug}")
    public Response getReactions(@PathParam("slug") String slug) {
        if (slug == null || slug.isBlank()) {
            throw new BadRequestException("文章标识不能为空");
        }
        Map<String, Long> counts = reactionService.getReactions(slug);
        return Response.ok(Map.of("success", true, "data", counts))
                .header("Cache-Control", "no-cache").build();
    }

    @POST
    @Path("/{slug}")
    public Response react(
            @PathParam("slug") String slug,
            ReactionRequest request,
            @Context io.vertx.ext.web.RoutingContext context) {
        if (slug == null || slug.isBlank()) {
            throw new BadRequestException("文章标识不能为空");
        }
        if (request == null || request.reactionType() == null) {
            throw new BadRequestException("请提供反应类型");
        }

        checkRate(context, "action", 30);

        boolean isReact = !"unreact".equalsIgnoreCase(request.action());

        // PoW verification: Mandatory for positive reactions
        if (isReact) {
            if (request.challengeId() == null || request.challengeId().isBlank()
                    || request.powNonce() == null || request.powNonce().isBlank()) {
                throw new BadRequestException("缺少 PoW 工作量证明参数");
            }
            captchaService.verify(request.challengeId(), request.powNonce());
        }

        Map<String, Long> updated = reactionService.updateReaction(slug, request.reactionType(), request.action());
        return Response.ok(Map.of("success", true, "data", updated)).build();
    }

    @RegisterForReflection
    public record ReactionRequest(
            String reactionType,
            String action,
            String challengeId,
            String powNonce
    ) {}
}
