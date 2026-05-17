package com.biliwind.blog.controller.api.admin.dto;

import java.time.OffsetDateTime;

public class AdminEdgeNodeDtos {

    public record NodeCertificateResponse(
            String nodeId,
            String primaryCertificatePem,
            String primaryPrivateKeyPem,
            String backupCertificatePem,
            String backupPrivateKeyPem,
            String caCertificatePem,
            OffsetDateTime primaryExpiry,
            OffsetDateTime backupExpiry
    ) {
    }

    public record NodeStatusResponse(
            String nodeId,
            String status,
            boolean isTrusted,
            String certificateSerial,
            OffsetDateTime certificateExpiry,
            boolean isRevoked
    ) {
    }
}
