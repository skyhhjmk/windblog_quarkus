package com.biliwind.blog.service.security;

import io.grpc.netty.GrpcSslContexts;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@ApplicationScoped
public class EdgeCertificateInstaller {

    @ConfigProperty(name = "windblog.grpc.client.ca-certificate", defaultValue = "certs/ca/ca.crt")
    String caCertificatePath;

    @ConfigProperty(name = "windblog.grpc.client.certificate", defaultValue = "certs/ca/server.crt")
    String certificatePath;

    @ConfigProperty(name = "windblog.grpc.client.key", defaultValue = "certs/ca/server.key")
    String privateKeyPath;

    public void install(String certificatePem, String privateKeyPem, String caCertificatePem) throws Exception {
        Path certificateFile = Path.of(certificatePath);
        Path privateKeyFile = Path.of(privateKeyPath);
        Path caCertificateFile = Path.of(caCertificatePath);

        createParentDirectory(certificateFile);
        createParentDirectory(privateKeyFile);
        createParentDirectory(caCertificateFile);

        Path temporaryCertificateFile = createTemporaryFile(certificateFile, certificatePem);
        Path temporaryPrivateKeyFile = createTemporaryFile(privateKeyFile, privateKeyPem);
        Path temporaryCaCertificateFile = createTemporaryFile(caCertificateFile, caCertificatePem);

        try {
            validateCertificateFiles(
                    temporaryCertificateFile,
                    temporaryPrivateKeyFile,
                    temporaryCaCertificateFile
            );
            replaceFile(temporaryCaCertificateFile, caCertificateFile);
            replaceFile(temporaryPrivateKeyFile, privateKeyFile);
            replaceFile(temporaryCertificateFile, certificateFile);
        } finally {
            Files.deleteIfExists(temporaryCertificateFile);
            Files.deleteIfExists(temporaryPrivateKeyFile);
            Files.deleteIfExists(temporaryCaCertificateFile);
        }
    }

    private void createParentDirectory(Path filePath) throws Exception {
        Path parentDirectory = filePath.toAbsolutePath().getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
    }

    private Path createTemporaryFile(Path targetFile, String content) throws Exception {
        Path parentDirectory = targetFile.toAbsolutePath().getParent();
        String fileName = targetFile.getFileName().toString();
        Path temporaryFile = Files.createTempFile(parentDirectory, fileName + ".", ".renewing");
        Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
        return temporaryFile;
    }

    private void validateCertificateFiles(
            Path certificateFile,
            Path privateKeyFile,
            Path caCertificateFile
    ) throws Exception {
        validateCertificateAndPrivateKey(certificateFile, privateKeyFile);
        GrpcSslContexts.forClient()
                .trustManager(caCertificateFile.toFile())
                .keyManager(certificateFile.toFile(), privateKeyFile.toFile())
                .build();
    }

    private void validateCertificateAndPrivateKey(
            Path certificateFile,
            Path privateKeyFile
    ) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (java.io.InputStream certificateInputStream = Files.newInputStream(certificateFile)) {
            certificate = (X509Certificate) certificateFactory.generateCertificate(certificateInputStream);
        }

        String privateKeyPem = Files.readString(privateKeyFile, StandardCharsets.UTF_8);
        String encodedPrivateKey = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] privateKeyBytes = Base64.getDecoder().decode(encodedPrivateKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

        byte[] verificationContent = "windblog-certificate-renewal".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(verificationContent);
        byte[] signatureBytes = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(verificationContent);
        if (!verifier.verify(signatureBytes)) {
            throw new IllegalArgumentException("证书与私钥不匹配");
        }
    }

    private void replaceFile(Path sourceFile, Path targetFile) throws Exception {
        try {
            Files.move(
                    sourceFile,
                    targetFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
