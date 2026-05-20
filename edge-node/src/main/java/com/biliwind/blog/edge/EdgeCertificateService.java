package com.biliwind.blog.edge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

@ApplicationScoped
public class EdgeCertificateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeCertificateService.class);
    private static final String PROVIDER = BouncyCastleProvider.PROVIDER_NAME;
    private static final String CERT_DIRECTORY = "certs";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @PostConstruct
    public void init() {
        try {
            File directory = new File(CERT_DIRECTORY);
            if (!directory.exists()) {
                directory.mkdirs();
                LOGGER.info("创建边缘节点证书目录: {}", CERT_DIRECTORY);
            }

            generateCAIfNeeded();
            generateServerCertificateIfNeeded();
            generateClientCertificateIfNeeded();

            LOGGER.info("边缘节点证书初始化完成，证书目录: {}", CERT_DIRECTORY);
        } catch (Exception e) {
            LOGGER.error("初始化边缘节点证书服务失败: {}", e.getMessage(), e);
        }
    }

    private boolean isCertFileExpired(File certFile) {
        try {
            byte[] certBytes = Files.readAllBytes(certFile.toPath());
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
            cert.checkValidity();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void generateCAIfNeeded() throws Exception {
        File caKeyFile = new File(CERT_DIRECTORY + "/ca.key");
        File caCertFile = new File(CERT_DIRECTORY + "/ca.crt");

        if (caKeyFile.exists() && caCertFile.exists() && !isCertFileExpired(caCertFile)) {
            LOGGER.info("边缘节点 CA 证书已存在且未过期，跳过生成");
            return;
        }

        LOGGER.info("正在生成边缘节点 CA 证书...");

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
        keyGen.initialize(4096);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Name issuer = new X500Name("CN=WindBlog Edge CA, O=WindBlog Edge Node, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 3650L * 24L * 60L * 60L * 1000L);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(keyPair.getPrivate());
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        Files.write(caKeyFile.toPath(), toPem(keyPair.getPrivate()).getBytes(StandardCharsets.UTF_8));
        Files.write(caCertFile.toPath(), toPem(cert).getBytes(StandardCharsets.UTF_8));

        LOGGER.info("边缘节点 CA 证书已生成");
    }

    private void generateServerCertificateIfNeeded() throws Exception {
        File serverKeyFile = new File(CERT_DIRECTORY + "/server.key");
        File serverCertFile = new File(CERT_DIRECTORY + "/server.crt");

        if (serverKeyFile.exists() && serverCertFile.exists() && !isCertFileExpired(serverCertFile)) {
            LOGGER.info("边缘节点服务器证书已存在且未过期，跳过生成");
            return;
        }

        File caKeyFile = new File(CERT_DIRECTORY + "/ca.key");
        File caCertFile = new File(CERT_DIRECTORY + "/ca.crt");
        if (!caKeyFile.exists() || !caCertFile.exists()) {
            LOGGER.error("无法生成服务器证书：CA 证书不存在");
            return;
        }

        LOGGER.info("正在生成边缘节点服务器证书...");

        KeyPair keyPair = generateKeyPair(2048);
        java.security.PrivateKey caPrivateKey = loadPrivateKey(caKeyFile);
        X509Certificate caCert = loadCertificate(caCertFile);

        X500Name caSubject = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name serverSubject = new X500Name("CN=edge-node-server, O=WindBlog Edge Node, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24L * 60L * 60L * 1000L);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                caSubject, serial, notBefore, notAfter, serverSubject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        Files.write(serverKeyFile.toPath(), toPem(keyPair.getPrivate()).getBytes(StandardCharsets.UTF_8));
        Files.write(serverCertFile.toPath(), toPem(cert).getBytes(StandardCharsets.UTF_8));

        LOGGER.info("边缘节点服务器证书已生成");
    }

    private void generateClientCertificateIfNeeded() throws Exception {
        File clientKeyFile = new File(CERT_DIRECTORY + "/client.key");
        File clientCertFile = new File(CERT_DIRECTORY + "/client.crt");

        if (clientKeyFile.exists() && clientCertFile.exists() && !isCertFileExpired(clientCertFile)) {
            LOGGER.info("边缘节点客户端证书已存在且未过期，跳过生成");
            return;
        }

        File caKeyFile = new File(CERT_DIRECTORY + "/ca.key");
        File caCertFile = new File(CERT_DIRECTORY + "/ca.crt");
        if (!caKeyFile.exists() || !caCertFile.exists()) {
            LOGGER.error("无法生成客户端证书：CA 证书不存在");
            return;
        }

        LOGGER.info("正在生成边缘节点客户端证书...");

        KeyPair keyPair = generateKeyPair(2048);
        java.security.PrivateKey caPrivateKey = loadPrivateKey(caKeyFile);
        X509Certificate caCert = loadCertificate(caCertFile);

        X500Name caSubject = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name clientSubject = new X500Name("CN=edge-node-client, O=WindBlog Edge Node, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24L * 60L * 60L * 1000L);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                caSubject, serial, notBefore, notAfter, clientSubject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(caPrivateKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder);

        Files.write(clientKeyFile.toPath(), toPem(keyPair.getPrivate()).getBytes(StandardCharsets.UTF_8));
        Files.write(clientCertFile.toPath(), toPem(cert).getBytes(StandardCharsets.UTF_8));

        LOGGER.info("边缘节点客户端证书已生成");
    }

    private KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
        keyGen.initialize(keySize);
        return keyGen.generateKeyPair();
    }

    private java.security.PrivateKey loadPrivateKey(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.contains("-----BEGIN")) {
            content = content.replace("-----BEGIN PRIVATE KEY-----", "");
            content = content.replace("-----END PRIVATE KEY-----", "");
            content = content.replace("-----BEGIN RSA PRIVATE KEY-----", "");
            content = content.replace("-----END RSA PRIVATE KEY-----", "");
            content = content.replaceAll("\\s+", "");
            byte[] derBytes = java.util.Base64.getDecoder().decode(content);
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(derBytes);
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } else {
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(bytes);
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        }
    }

    private X509Certificate loadCertificate(File file) throws Exception {
        byte[] certBytes = Files.readAllBytes(file.toPath());
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
    }

    private String toPem(Object object) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(object);
        }
        return sw.toString();
    }
}
