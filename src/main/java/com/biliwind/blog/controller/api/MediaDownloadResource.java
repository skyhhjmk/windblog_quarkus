package com.biliwind.blog.controller.api;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.ContentAccessTicket;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ContentAccessTicketService;
import com.biliwind.blog.service.MediaDownloadAuditService;
import com.biliwind.blog.service.MediaDownloadRiskService;
import com.biliwind.blog.service.MediaAccessPolicy;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.biliwind.blog.common.security.UserTokenVerifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Path("/api/media/download")
public class MediaDownloadResource {

    @ConfigProperty(name = "windblog.media.download.max-bytes", defaultValue = "536870912")
    long maxSingleDownloadBytes;

    @Inject
    ContentAccessTicketService ticketService;

    @Inject
    MediaDownloadAuditService downloadAuditService;

    @Inject
    MediaDownloadRiskService downloadRiskService;

    @Inject
    StorageService storageService;

    @Inject
    MediaAccessPolicy mediaAccessPolicy;

    @Inject
    RegionContext regionContext;

    @Inject
    UserTokenVerifier tokenVerifier;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @GET
    @Path("/{token}")
    public Response download(@PathParam("token") String token, @Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        ContentAccessTicket ticket = ticketService.requireMediaTicket(
                token, userId, headers.getHeaderString("X-Device-Id"));
        if (ticket == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Long mediaId = resolveMediaId(ticket);
        if (mediaId == null || ticket.postId == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Media media = Media.find("id = ?1 and deletedAt is null", mediaId).firstResult();
        PostMedia relation = media == null ? null : PostMedia.find(
                "select relation from PostMedia relation join fetch relation.post "
                        + "where relation.media.id = ?1 and relation.post.id = ?2",
                media.id, ticket.postId).firstResult();
        if (relation == null) {
            downloadAuditService.recordDenied(ticket, mediaId,
                    resolveClientIp(headers), headers.getHeaderString("User-Agent"),
                    headers.getHeaderString("Referer"), "TICKET_RESOURCE_MISMATCH");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String clientIp = resolveClientIp(headers);
        MediaDownloadRiskService.Decision risk = downloadRiskService.check(
                userId, ticket.postId, clientIp, media.size);
        if (!risk.allowed()) {
            downloadAuditService.recordDenied(ticket, media.id,
                    clientIp, headers.getHeaderString("User-Agent"), headers.getHeaderString("Referer"),
                    risk.reason());
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", risk.retryAfterSeconds())
                    .header("Cache-Control", "no-store")
                    .build();
        }
        MediaDownloadRiskService.DownloadLease concurrencyLease =
                downloadRiskService.tryAcquireConcurrency(userId, ticket.postId, clientIp);
        if (concurrencyLease == null) {
            downloadAuditService.recordDenied(ticket, media.id,
                    clientIp, headers.getHeaderString("User-Agent"), headers.getHeaderString("Referer"),
                    "CONCURRENT_LIMIT");
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", 60)
                    .header("Cache-Control", "no-store")
                    .build();
        }
        Post post = relation.post;
        MediaAccessPolicy.Decision access = mediaAccessPolicy.authorizeDownload(
                media, post, userId, regionContext.getCurrentRegion());
        if (!access.allowed()) {
            downloadRiskService.releaseConcurrency(concurrencyLease);
            downloadAuditService.recordDenied(ticket, media.id,
                    clientIp, headers.getHeaderString("User-Agent"), headers.getHeaderString("Referer"),
                    access.reason());
            Response.Status status = "PURCHASE_REQUIRED".equals(access.reason())
                    ? Response.Status.FORBIDDEN
                    : Response.Status.NOT_FOUND;
            return Response.status(status).build();
        }

        try {
            InputStream input = storageService.fallbackDownload(media, VariantType.ORIGINAL);
            StreamingOutput output = stream -> {
                long bytesSent = 0L;
                try (InputStream source = input) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = source.read(buffer)) >= 0) {
                        if (bytesRead == 0) {
                            continue;
                        }
                        try {
                            bytesSent = writeLimitedChunk(stream, buffer, bytesRead,
                                    bytesSent, maxSingleDownloadBytes);
                        } catch (java.io.IOException exception) {
                            if (exception.getMessage() != null
                                    && exception.getMessage().contains("字节上限")) {
                                bytesSent = maxSingleDownloadBytes;
                            }
                            throw exception;
                        }
                    }
                    stream.flush();
                    downloadAuditService.recordAllowed(ticket, media.id, clientIp,
                            headers.getHeaderString("User-Agent"), headers.getHeaderString("Referer"), bytesSent);
                } catch (java.io.IOException exception) {
                    String failureReason = "STREAM_FAILED";
                    if (exception.getMessage() != null
                            && exception.getMessage().contains("字节上限")) {
                        failureReason = "SIZE_LIMIT";
                    }
                    downloadAuditService.recordFailed(ticket, media.id, userId, clientIp,
                            headers.getHeaderString("User-Agent"), bytesSent, failureReason);
                    throw exception;
                } finally {
                    downloadRiskService.releaseConcurrency(concurrencyLease);
                }
            };
            String fileName = media.fileName == null || media.fileName.isBlank() ? "download" : media.fileName;
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return Response.ok(output)
                    .type(media.mimeType == null ? MediaType.APPLICATION_OCTET_STREAM : media.mimeType)
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName)
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        } catch (Exception exception) {
            downloadRiskService.releaseConcurrency(concurrencyLease);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    private Long resolveUserId(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        return verified == null ? null : verified.uid();
    }

    private String resolveClientIp(HttpHeaders headers) {
        if (routingContext != null) {
            return clientIpResolver.resolve(routingContext).clientIp();
        }
        return "unknown";
    }

    private Long resolveMediaId(ContentAccessTicket ticket) {
        try {
            return Long.valueOf(ticket.scope.substring("MEDIA_DOWNLOAD:".length()));
        } catch (Exception exception) {
            return null;
        }
    }

    static long writeLimitedChunk(OutputStream output, byte[] buffer, int bytesRead,
                                  long bytesSent, long maxBytes) throws java.io.IOException {
        if (output == null || buffer == null || bytesRead < 0 || bytesRead > buffer.length
                || bytesSent < 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("下载流参数无效");
        }
        long remainingBytes = maxBytes - bytesSent;
        if (remainingBytes <= 0) {
            throw new java.io.IOException("受保护下载超过单文件字节上限");
        }
        if (bytesRead > remainingBytes) {
            output.write(buffer, 0, (int) remainingBytes);
            throw new java.io.IOException("受保护下载超过单文件字节上限");
        }
        output.write(buffer, 0, bytesRead);
        return bytesSent + bytesRead;
    }
}
