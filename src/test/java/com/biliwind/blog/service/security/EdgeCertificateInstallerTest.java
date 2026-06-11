package com.biliwind.blog.service.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EdgeCertificateInstallerTest {

    @Inject
    CertificateService certificateService;

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInstallValidCertificateFiles() throws Exception {
        EdgeCertificateInstaller certificateInstaller = createCertificateInstaller();
        CertificateService.GeneratedCertificate generatedCertificate =
                certificateService.generateNodeCertificate("test-edge", 72);

        certificateInstaller.install(
                generatedCertificate.certificatePem(),
                generatedCertificate.privateKeyPem(),
                generatedCertificate.caCertificatePem()
        );

        assertEquals(
                generatedCertificate.certificatePem(),
                Files.readString(temporaryDirectory.resolve("server.crt"))
        );
        assertEquals(
                generatedCertificate.privateKeyPem(),
                Files.readString(temporaryDirectory.resolve("server.key"))
        );
        assertEquals(
                generatedCertificate.caCertificatePem(),
                Files.readString(temporaryDirectory.resolve("ca.crt"))
        );
    }

    @Test
    void shouldKeepCurrentFilesWhenCertificateAndKeyDoNotMatch() throws Exception {
        EdgeCertificateInstaller certificateInstaller = createCertificateInstaller();
        CertificateService.GeneratedCertificate currentCertificate =
                certificateService.generateNodeCertificate("test-edge", 72);
        CertificateService.GeneratedCertificate differentCertificate =
                certificateService.generateNodeCertificate("test-edge", 72);

        certificateInstaller.install(
                currentCertificate.certificatePem(),
                currentCertificate.privateKeyPem(),
                currentCertificate.caCertificatePem()
        );

        assertThrows(
                Exception.class,
                () -> certificateInstaller.install(
                        currentCertificate.certificatePem(),
                        differentCertificate.privateKeyPem(),
                        currentCertificate.caCertificatePem()
                )
        );
        assertEquals(
                currentCertificate.certificatePem(),
                Files.readString(temporaryDirectory.resolve("server.crt"))
        );
        assertEquals(
                currentCertificate.privateKeyPem(),
                Files.readString(temporaryDirectory.resolve("server.key"))
        );
    }

    private EdgeCertificateInstaller createCertificateInstaller() {
        EdgeCertificateInstaller certificateInstaller = new EdgeCertificateInstaller();
        certificateInstaller.certificatePath = temporaryDirectory.resolve("server.crt").toString();
        certificateInstaller.privateKeyPath = temporaryDirectory.resolve("server.key").toString();
        certificateInstaller.caCertificatePath = temporaryDirectory.resolve("ca.crt").toString();
        return certificateInstaller;
    }
}
