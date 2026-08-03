package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.ContentAccessTicket;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.service.ContentAccessTicketService;
import com.biliwind.blog.service.MediaDownloadAuditService;
import com.biliwind.blog.service.MediaDownloadRiskService;
import com.biliwind.blog.service.PostAccessService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.biliwind.blog.context.AdminRequestContext;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Path("/api/admin/media/download")
public class AdminMediaDownloadResource {

    @ConfigProperty(name = "windblog.media.download.max-bytes", defaultValue = "536870912")
    long maxSingleDownloadBytes;

    @Inject
    ContentAccessTicketService ticketService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    MediaDownloadAuditService downloadAuditService;

    @Inject
    MediaDownloadRiskService downloadRiskService;

    @Inject
    StorageService storageService;

    @Inject
    PostAccessService postAccessService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @GET
    @Path("/{token}")
    public Response download(@PathParam("token") String token,
                             @HeaderParam("User-Agent") String userAgent,
                             @HeaderParam("Referer") String referer) {
        Long adminId = adminRequestContext.getUserId();
        ContentAccessTicket ticket = ticketService.consumeAdminMediaDownloadTicket(token, adminId);
        if (ticket == null) {
            return notFound();
        }
        Long mediaId = resolveMediaId(ticket);
        if (mediaId == null || ticket.postId == null) {
            return notFound();
        }
        Media media = Media.find("id = ?1 and deletedAt is null", mediaId).firstResult();
        PostMedia relation = media == null ? null : PostMedia.find(
                "select relation from PostMedia relation join fetch relation.post "
                        + "where relation.media.id = ?1 and relation.post.id = ?2",
                media.id, ticket.postId).firstResult();
        if (relation == null || !postAccessService.isProtectedMediaReference(relation)) {
            downloadAuditService.recordDenied(ticket, mediaId,
                    resolveClientIp(), userAgent, referer, "TICKET_RESOURCE_MISMATCH");
            return notFound();
        }

        String clientIp = resolveClientIp();
        MediaDownloadRiskService.Decision risk = downloadRiskService.check(
                adminId, ticket.postId, clientIp, media.size, ticket.id);
        if (!risk.allowed()) {
            downloadAuditService.recordDenied(ticket, media.id,
                    clientIp, userAgent, referer, risk.reason());
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", risk.retryAfterSeconds())
                    .header("Cache-Control", "no-store")
                    .build();
        }
        MediaDownloadRiskService.DownloadLease concurrencyLease =
                downloadRiskService.tryAcquireConcurrency(adminId, ticket.postId, clientIp);
        if (concurrencyLease == null) {
            downloadAuditService.recordDenied(ticket, media.id,
                    clientIp, userAgent, referer, "CONCURRENT_LIMIT");
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", 60)
                    .header("Cache-Control", "no-store")
                    .build();
        }

        try {
            InputStream input = storageService.fallbackDownload(media, VariantType.ORIGINAL);
            StreamingOutput output = stream -> streamDownload(
                    stream, input, ticket, media, adminId, clientIp, userAgent, referer, concurrencyLease);
            String fileName = media.fileName == null || media.fileName.isBlank()
                    ? "download" : media.fileName;
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return Response.ok(output)
                    .type(media.mimeType == null ? MediaType.APPLICATION_OCTET_STREAM : media.mimeType)
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName)
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
        } catch (Exception exception) {
            downloadRiskService.releaseConcurrency(concurrencyLease);
            downloadAuditService.recordFailed(ticket, media.id, adminId, clientIp,
                    userAgent, 0L, "STORAGE_FAILED");
            return notFound();
        }
    }

    private void streamDownload(OutputStream output, InputStream input, ContentAccessTicket ticket,
                                Media media, Long adminId, String clientIp, String userAgent,
                                String referer, MediaDownloadRiskService.DownloadLease lease)
            throws java.io.IOException {
        long bytesSent = 0L;
        try (InputStream source = input) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = source.read(buffer)) >= 0) {
                if (bytesRead == 0) {
                    continue;
                }
                bytesSent = writeLimitedChunk(output, buffer, bytesRead, bytesSent, maxSingleDownloadBytes);
            }
            output.flush();
            downloadAuditService.recordAllowed(ticket, media.id, clientIp, userAgent, referer, bytesSent);
        } catch (java.io.IOException exception) {
            downloadAuditService.recordFailed(ticket, media.id, adminId, clientIp,
                    userAgent, bytesSent, "STREAM_FAILED");
            throw exception;
        } finally {
            downloadRiskService.releaseConcurrency(lease);
        }
    }

    private Long resolveMediaId(ContentAccessTicket ticket) {
        try {
            return Long.valueOf(ticket.scope.substring("ADMIN_MEDIA_DOWNLOAD:".length()));
        } catch (Exception exception) {
            return null;
        }
    }

    private String resolveClientIp() {
        if (routingContext != null) {
            return clientIpResolver.resolve(routingContext).clientIp();
        }
        return "unknown";
    }

    private Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .header("Cache-Control", "no-store")
                .build();
    }

    static long writeLimitedChunk(OutputStream output, byte[] buffer, int bytesRead,
                                  long bytesSent, long maxBytes) throws java.io.IOException {
        if (output == null || buffer == null || bytesRead < 0 || bytesRead > buffer.length
                || bytesSent < 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("下载流参数无效");
        }
        long remainingBytes = maxBytes - bytesSent;
        if (remainingBytes <= 0) {
            throw new java.io.IOException("管理原图下载超过单文件字节上限");
        }
        if (bytesRead > remainingBytes) {
            output.write(buffer, 0, (int) remainingBytes);
            throw new java.io.IOException("管理原图下载超过单文件字节上限");
        }
        output.write(buffer, 0, bytesRead);
        return bytesSent + bytesRead;
    }
}
