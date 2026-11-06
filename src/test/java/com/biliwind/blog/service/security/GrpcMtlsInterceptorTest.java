package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcMtlsInterceptorTest {

    @Test
    void shouldPinnedPrimaryClientCertificateBeAcceptedOnEdge() {
        GrpcMtlsInterceptor interceptor = new GrpcMtlsInterceptor();
        interceptor.expectedPrimaryClientCommonName = "main-node-client,windblog-local-client";
        interceptor.primaryNodeId = "main";

        GrpcMtlsInterceptor.CertificateValidationResult accepted =
                interceptor.validatePrimaryClientCertificate("main-node-client");
        assertTrue(accepted.valid);
        assertEquals("main", accepted.nodeId);
        assertTrue(interceptor.validatePrimaryClientCertificate("windblog-local-client").valid);
    }

    @Test
    void shouldRejectAnotherCaSignedEdgeCertificateOnEdge() {
        GrpcMtlsInterceptor interceptor = new GrpcMtlsInterceptor();
        interceptor.expectedPrimaryClientCommonName = "main-node-client";
        interceptor.primaryNodeId = "main";

        assertFalse(interceptor.validatePrimaryClientCertificate("edge-home-1").valid);
        assertFalse(interceptor.validatePrimaryClientCertificate("").valid);
        assertFalse(interceptor.validatePrimaryClientCertificate(null).valid);
    }

    @Test
    void shouldFailClosedWhenExpectedCommonNameIsBlank() {
        GrpcMtlsInterceptor interceptor = new GrpcMtlsInterceptor();
        interceptor.expectedPrimaryClientCommonName = "  ";
        interceptor.primaryNodeId = "main";

        assertFalse(interceptor.validatePrimaryClientCertificate("main-node-client").valid);
    }
}
