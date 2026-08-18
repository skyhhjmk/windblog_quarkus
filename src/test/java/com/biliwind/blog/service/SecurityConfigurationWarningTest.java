package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigurationWarningTest {

    @Test
    void shouldAcceptCompletePrimaryProductionConfiguration() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();

        assertDoesNotThrow(warning::validateProductionConfiguration);
    }

    @Test
    void shouldAllowOneTimeAdminBootstrapInProduction() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.adminInitializationEnabled = true;

        assertDoesNotThrow(warning::validateProductionConfiguration);
    }

    @Test
    void shouldRejectWildcardCorsOrigin() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.corsOrigins = "https://blog.example.com,*";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("CORS_ORIGINS"));
    }

    @Test
    void shouldRejectDisabledRequiredVirusScan() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.mediaVirusScanRequired = false;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("病毒扫描"));
    }

    @Test
    void shouldRejectWeakRabbitCredentials() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.rabbitmqPassword = "short";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("RabbitMQ"));
    }

    @Test
    void shouldRejectInvalidEdgeRoutedBodyLimit() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.edgeMaxRoutedBodyBytes = 0;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("边缘回源请求体上限"));
    }

    @Test
    void shouldRejectInsecureCookieAndNonHttpsPublicUrl() {
        SecurityConfigurationWarning insecureCookie = validPrimaryConfiguration();
        insecureCookie.cookieSecure = false;
        assertThrows(IllegalStateException.class, insecureCookie::validateProductionConfiguration);

        SecurityConfigurationWarning insecureUrl = validPrimaryConfiguration();
        insecureUrl.publicUrl = "http://blog.example.com";
        assertThrows(IllegalStateException.class, insecureUrl::validateProductionConfiguration);
    }

    @Test
    void shouldRejectWeakJwtAndEventSecrets() {
        SecurityConfigurationWarning weakJwt = validPrimaryConfiguration();
        weakJwt.adminJwtSecret = "change-me-admin-secret";
        assertThrows(IllegalStateException.class, weakJwt::validateProductionConfiguration);

        SecurityConfigurationWarning shortEventSecret = validPrimaryConfiguration();
        shortEventSecret.eventHashSecret = "too-short";
        assertThrows(IllegalStateException.class, shortEventSecret::validateProductionConfiguration);
    }

    @Test
    void shouldRejectElasticsearchTrustAllInProduction() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.elasticsearchSslTrustAll = true;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("Elasticsearch"));
    }

    @Test
    void shouldRejectIncompleteElasticsearchTlsVerificationInProduction() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.elasticsearchSslVerify = "none";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("完整 TLS"));
    }

    @Test
    void shouldRejectGrpcPlaintextInEveryRuntimeProfile() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.grpcServerPlaintext = true;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateGrpcSecurityConfiguration);

        assertTrue(exception.getMessage().contains("明文"));
    }

    @Test
    void shouldRejectGrpcPlaintextFallbackInEveryRuntimeProfile() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.grpcClientPlaintextFallbackAllowed = true;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateGrpcSecurityConfiguration);

        assertTrue(exception.getMessage().contains("回退"));
    }

    @Test
    void shouldRejectDefaultSecretEvenWhenWarningsAreDisabled() {
        SecurityConfigurationWarning warning = validPrimaryConfiguration();
        warning.adminInitPassword = "admin";
        warning.warnDefaultSecrets = false;
        warning.failOnDefaultSecretsInProd = true;

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                warning::validateProductionConfiguration);

        assertTrue(exception.getMessage().contains("默认安全配置"));
    }

    @Test
    void shouldRejectPrivateOrCredentialBearingPublicUrls() {
        SecurityConfigurationWarning privateAddress = validPrimaryConfiguration();
        privateAddress.publicUrl = "https://192.168.1.20";
        assertThrows(IllegalStateException.class, privateAddress::validateProductionConfiguration);

        SecurityConfigurationWarning loopbackAddress = validPrimaryConfiguration();
        loopbackAddress.publicUrl = "https://[::1]";
        assertThrows(IllegalStateException.class, loopbackAddress::validateProductionConfiguration);

        SecurityConfigurationWarning credentialBearingUrl = validPrimaryConfiguration();
        credentialBearingUrl.publicUrl = "https://user:password@blog.example.com";
        assertThrows(IllegalStateException.class, credentialBearingUrl::validateProductionConfiguration);

        SecurityConfigurationWarning mappedPrivateAddress = validPrimaryConfiguration();
        mappedPrivateAddress.publicUrl = "https://[::ffff:192.168.1.20]";
        assertThrows(IllegalStateException.class, mappedPrivateAddress::validateProductionConfiguration);

        SecurityConfigurationWarning compressedMappedPrivateAddress = validPrimaryConfiguration();
        compressedMappedPrivateAddress.publicUrl = "https://[::ffff:c0a8:114]";
        assertThrows(IllegalStateException.class, compressedMappedPrivateAddress::validateProductionConfiguration);

        SecurityConfigurationWarning cgnatAddress = validPrimaryConfiguration();
        cgnatAddress.publicUrl = "https://100.64.0.1";
        assertThrows(IllegalStateException.class, cgnatAddress::validateProductionConfiguration);
    }

    @Test
    void shouldRejectInsecureOrPathBearingCorsOrigins() {
        SecurityConfigurationWarning httpOrigin = validPrimaryConfiguration();
        httpOrigin.corsOrigins = "http://admin.example.com";
        assertThrows(IllegalStateException.class, httpOrigin::validateProductionConfiguration);

        SecurityConfigurationWarning pathOrigin = validPrimaryConfiguration();
        pathOrigin.corsOrigins = "https://admin.example.com/console";
        assertThrows(IllegalStateException.class, pathOrigin::validateProductionConfiguration);

        SecurityConfigurationWarning privateOrigin = validPrimaryConfiguration();
        privateOrigin.corsOrigins = "https://192.168.1.20";
        assertThrows(IllegalStateException.class, privateOrigin::validateProductionConfiguration);
    }

    private SecurityConfigurationWarning validPrimaryConfiguration() {
        SecurityConfigurationWarning warning = new SecurityConfigurationWarning();
        warning.cookieSecure = true;
        warning.publicUrl = "https://blog.example.com";
        warning.corsOrigins = "https://admin.example.com";
        warning.rabbitmqUsername = "windblog";
        warning.rabbitmqPassword = "rabbit-value-that-is-longer-than-32";
        warning.adminInitPassword = "initial-admin-password-that-is-long";
        warning.failOnDefaultSecretsInProd = true;
        warning.eventHashSecret = "event-hash-value-that-is-longer-than-32";
        warning.adminJwtSecret = "admin-jwt-value-that-is-longer-than-32";
        warning.userJwtSecret = "user-jwt-value-that-is-longer-than-32";
        warning.swaggerEnabled = false;
        warning.cspEnforced = true;
        warning.hstsEnabled = true;
        warning.trustedTypesEnabled = true;
        warning.cspImageSources = "none";
        warning.cspConnectSources = "none";
        warning.corsCredentialsAllowed = false;
        warning.elasticsearchSslVerify = "full";
        warning.adminInitializationEnabled = false;
        warning.nodeRole = "primary";
        warning.grpcServerPlaintext = false;
        warning.grpcServerClientAuth = "required";
        warning.grpcServerCertificate = java.util.Optional.of("server.crt");
        warning.grpcServerKey = java.util.Optional.of("server.key");
        warning.grpcServerTrustStore = java.util.Optional.of("truststore.p12");
        warning.grpcServerTrustStorePassword = java.util.Optional.of("trust-store-password");
        warning.grpcClientPlaintextFallbackAllowed = false;
        warning.mediaVirusScanEnabled = true;
        warning.mediaVirusScanRequired = true;
        warning.edgeMaxRoutedBodyBytes = 10L * 1024L * 1024L;
        return warning;
    }
}
