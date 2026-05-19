package com.biliwind.blog.service.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Date;

/**
 * 证书服务，负责根 CA 初始化和边缘节点证书签发。
 * 使用 BouncyCastle 实现 X.509 证书管理。
 */
@ApplicationScoped
public class CertificateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateService.class);
    private static final String CERT_DIRECTORY = "certs/ca";
    private static final String PROVIDER = BouncyCastleProvider.PROVIDER_NAME;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PrivateKey caPrivateKey;
    private X509Certificate caCertificate;

    @PostConstruct
    public void init() {
        try {
            File directory = new File(CERT_DIRECTORY);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            File caKeyFile = new File(CERT_DIRECTORY + "/ca.key");
            File caCertFile = new File(CERT_DIRECTORY + "/ca.crt");

            boolean needNewCA = false;
            if (caKeyFile.exists() && caCertFile.exists()) {
                loadCA(caKeyFile, caCertFile);
                if (isExpired(caCertificate)) {
                    LOGGER.warn("根 CA 证书已过期，正在重新生成...");
                    needNewCA = true;
                }
            } else {
                needNewCA = true;
            }

            if (needNewCA) {
                generateCA();
            }

            // 同时也为自己（主节点）生成一个服务器证书，用于 gRPC 服务端
            File serverKeyFile = new File(CERT_DIRECTORY + "/server.key");
            File serverCertFile = new File(CERT_DIRECTORY + "/server.crt");
            if (!serverKeyFile.exists() || !serverCertFile.exists() || isExpired(serverCertFile)) {
                generateServerCertificate("main-node", serverKeyFile, serverCertFile);
            }

            // 同时也为自己生成一个客户端证书，用于主动连接边缘节点
            File clientKeyFile = new File(CERT_DIRECTORY + "/client.key");
            File clientCertFile = new File(CERT_DIRECTORY + "/client.crt");
            if (!clientKeyFile.exists() || !clientCertFile.exists() || isExpired(clientCertFile)) {
                generateServerCertificate("main-node-client", clientKeyFile, clientCertFile);
            }

            // 执行热迁移，将遗留的二进制证书自动转为 PEM 格式
            migrateCertToPemIfNeeded(caCertFile);
            migrateKeyToPemIfNeeded(caKeyFile);
            migrateCertToPemIfNeeded(serverCertFile);
            migrateKeyToPemIfNeeded(serverKeyFile);
            migrateCertToPemIfNeeded(clientCertFile);
            migrateKeyToPemIfNeeded(clientKeyFile);
        } catch (Exception e) {
            LOGGER.error("初始化证书服务失败: {}", e.getMessage(), e);
        }
    }

    private boolean isExpired(X509Certificate cert) {
        try {
            cert.checkValidity();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void migrateCertToPemIfNeeded(File file) {
        try {
            if (!file.exists()) return;
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (!content.contains("-----BEGIN")) {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(bytes));
                Files.writeString(file.toPath(), toPem(cert));
                LOGGER.info("已将二进制证书文件 {} 转换为 PEM 格式", file.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("尝试转换证书 {} 到 PEM 格式失败: {}", file.getName(), e.getMessage());
        }
    }

    private void migrateKeyToPemIfNeeded(File file) {
        try {
            if (!file.exists()) return;
            byte[] bytes = Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (!content.contains("-----BEGIN")) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(bytes);
                PrivateKey key = keyFactory.generatePrivate(keySpec);
                Files.writeString(file.toPath(), toPem(key));
                LOGGER.info("已将二进制私钥文件 {} 转换为 PEM 格式", file.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("尝试转换私钥 {} 到 PEM 格式失败: {}", file.getName(), e.getMessage());
        }
    }

    private boolean isExpired(File certFile) {
        try {
            byte[] certBytes = Files.readAllBytes(certFile.toPath());
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
            return isExpired(cert);
        } catch (Exception e) {
            return true;
        }
    }

    private void loadCA(File keyFile, File certFile) throws Exception {
        byte[] certBytes = Files.readAllBytes(certFile.toPath());

        caPrivateKey = loadPrivateKey(keyFile);

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        caCertificate = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
        LOGGER.info("已加载根 CA 证书");
    }

    private void generateCA() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
        keyGen.initialize(4096);
        KeyPair keyPair = keyGen.generateKeyPair();
        caPrivateKey = keyPair.getPrivate();

        X500Name issuer = new X500Name("CN=WindBlog Root CA, O=Biliwind, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000); // 10 years

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        caCertificate = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        Files.write(new File(CERT_DIRECTORY + "/ca.key").toPath(), toPem(caPrivateKey).getBytes("UTF-8"));
        Files.write(new File(CERT_DIRECTORY + "/ca.crt").toPath(), toPem(caCertificate).getBytes("UTF-8"));

        LOGGER.info("已生成新的根 CA 证书");
    }

    /**
     * 为节点生成证书对
     *
     * @param nodeId        节点ID
     * @param validityHours 有效期（小时）
     * @return 包含证书和私钥的结果
     */
    public GeneratedCertificate generateNodeCertificate(String nodeId, int validityHours) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Name subject = new X500Name("CN=" + nodeId + ", O=WindBlog Edge Node, C=CN");
        // 使用更短的有效期，支持 24 小时和 72 小时
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 1000 * 60 * 10); // 10 mins ago
        Date notAfter = new Date(System.currentTimeMillis() + (long) validityHours * 60 * 60 * 1000);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name(caCertificate.getSubjectX500Principal().getName()),
                serial, notBefore, notAfter, subject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        certBuilder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caCertificate));
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.getPublic()));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        return new GeneratedCertificate(
                toPem(cert),
                toPem(keyPair.getPrivate()),
                toPem(caCertificate),
                serial.toString(),
                OffsetDateTime.now().plusHours(validityHours)
        );
    }

    private void generateServerCertificate(String commonName, File keyFile, File certFile) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Name subject = new X500Name("CN=" + commonName + ", O=WindBlog Main Node, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24 * 60 * 60 * 1000); // 1 year

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name(caCertificate.getSubjectX500Principal().getName()),
                serial, notBefore, notAfter, subject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        Files.write(keyFile.toPath(), toPem(keyPair.getPrivate()).getBytes("UTF-8"));
        Files.write(certFile.toPath(), toPem(cert).getBytes("UTF-8"));
        LOGGER.info("已为 {} 生成服务器证书", commonName);
    }

    private String toPem(Object object) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(object);
        }
        return sw.toString();
    }

    private PrivateKey loadPrivateKey(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String content = new String(bytes, "UTF-8");
        if (content.contains("-----BEGIN")) {
            content = content.replace("-----BEGIN PRIVATE KEY-----", "");
            content = content.replace("-----END PRIVATE KEY-----", "");
            content = content.replace("-----BEGIN RSA PRIVATE KEY-----", "");
            content = content.replace("-----END RSA PRIVATE KEY-----", "");
            content = content.replaceAll("\\s+", "");
            byte[] derBytes = java.util.Base64.getDecoder().decode(content);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(derBytes);
            return keyFactory.generatePrivate(keySpec);
        } else {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(bytes);
            return keyFactory.generatePrivate(keySpec);
        }
    }

    public record GeneratedCertificate(
            String certificatePem,
            String privateKeyPem,
            String caCertificatePem,
            String serialNumber,
            OffsetDateTime expiry
    ) {
    }
}
