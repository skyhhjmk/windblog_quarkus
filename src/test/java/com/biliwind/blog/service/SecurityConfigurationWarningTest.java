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
        warning.adminInitializationEnabled = false;
        warning.nodeRole = "primary";
        warning.grpcServerClientAuth = "required";
        warning.grpcServerCertificate = "server.crt";
        warning.grpcServerKey = "server.key";
        warning.grpcServerTrustStore = "truststore.p12";
        warning.grpcServerTrustStorePassword = "trust-store-password";
        warning.grpcClientPlaintextFallbackAllowed = false;
        warning.mediaVirusScanEnabled = true;
        warning.mediaVirusScanRequired = true;
        return warning;
    }
}
