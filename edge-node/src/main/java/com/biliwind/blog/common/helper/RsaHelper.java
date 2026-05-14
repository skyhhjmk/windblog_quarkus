package com.biliwind.blog.common.helper;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 助手类，用于处理系统中独立的密钥对、加密和解密。
 * 严格遵守普通人一眼看懂的编程规范，不使用任何高级、晦涩的语法。
 */
@ApplicationScoped
public class RsaHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(RsaHelper.class);
    private static final String KEY_DIRECTORY = "rsa_keys";
    private PrivateKey privateKey;
    private PublicKey publicKey;

    @PostConstruct
    public void init() {
        try {
            File privateKeyFile = new File(KEY_DIRECTORY + "/private.key");
            File publicKeyFile = new File(KEY_DIRECTORY + "/public.key");

            if (privateKeyFile.exists()) {
                if (publicKeyFile.exists()) {
                    loadKeys(privateKeyFile, publicKeyFile);
                } else {
                    generateAndSaveKeys();
                }
            } else {
                generateAndSaveKeys();
            }
        } catch (Exception e) {
            LOGGER.error("初始化 RSA 密钥失败: " + e.getMessage(), e);
        }
    }

    private void loadKeys(File privateKeyFile, File publicKeyFile) throws Exception {
        byte[] privateBytes = Files.readAllBytes(privateKeyFile.toPath());
        byte[] publicBytes = Files.readAllBytes(publicKeyFile.toPath());

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(privateBytes);
        privateKey = keyFactory.generatePrivate(privateSpec);

        X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(publicBytes);
        publicKey = keyFactory.generatePublic(publicSpec);
    }

    private void generateAndSaveKeys() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        privateKey = keyPair.getPrivate();
        publicKey = keyPair.getPublic();

        File directory = new File(KEY_DIRECTORY);
        if (directory.exists() == false) {
            directory.mkdirs();
        }

        Files.write(new File(KEY_DIRECTORY + "/private.key").toPath(), privateKey.getEncoded());
        Files.write(new File(KEY_DIRECTORY + "/public.key").toPath(), publicKey.getEncoded());

        LOGGER.info("已生成并保存新的 RSA 密钥对到 " + KEY_DIRECTORY);
    }

    private PublicKey clusterPublicKey;

    /**
     * 使用 RSA+AES 混合加密文本。
     * RSA 加密随机生成的 AES 密钥，AES 加密实际数据。
     * 解决了 RSA 直接加密的长度限制问题。
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            // 1. 生成 128 位随机 AES 密钥
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128);
            SecretKey aesKey = keyGen.generateKey();

            // 2. 使用 RSA 公钥加密该 AES 密钥
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");

            // 优先使用集群公钥，实现跨节点解密一致性
            PublicKey keyToUse = publicKey;
            if (clusterPublicKey != null) {
                keyToUse = clusterPublicKey;
            }

            rsaCipher.init(Cipher.ENCRYPT_MODE, keyToUse);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            // 3. 使用 AES 加密实际数据 (CBC 模式 + 随机 IV)
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec);
            byte[] encryptedData = aesCipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 4. 组合结果并使用 Base64 编码
            // 格式: H1:Base64(EncAesKey).Base64(IV).Base64(EncData)
            Base64.Encoder encoder = Base64.getEncoder();
            return "H1:" + encoder.encodeToString(encryptedAesKey) + "." +
                    encoder.encodeToString(iv) + "." +
                    encoder.encodeToString(encryptedData);
        } catch (Exception e) {
            LOGGER.error("混合加密失败: " + e.getMessage());
            return "EncryptionError: " + e.getMessage();
        }
    }

    /**
     * 获取 Base64 编码的公钥
     */
    public String getPublicKeyEncoded() {
        if (publicKey == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 设置集群公钥（用于边缘节点加密，主节点解密）
     */
    public void setClusterPublicKey(String base64Key) {
        if (base64Key == null || base64Key.isEmpty()) {
            return;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            this.clusterPublicKey = keyFactory.generatePublic(spec);
            LOGGER.info("已成功更新集群公钥");
        } catch (Exception e) {
            LOGGER.error("更新集群公钥失败: " + e.getMessage());
        }
    }


    /**
     * 解密文本，支持 H1 混合加密格式和旧的纯 RSA 格式。
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }

        // 判断是否为 H1 混合加密格式
        if (encryptedText.startsWith("H1:")) {
            return decryptHybrid(encryptedText);
        }

        // 回退到旧的 RSA 直接解密
        return decryptRsaOnly(encryptedText);
    }

    private String decryptHybrid(String encryptedText) {
        try {
            String content = encryptedText.substring(3);
            String[] parts = content.split("\\.");
            if (parts.length != 3) {
                throw new Exception("混合加密格式错误");
            }

            Base64.Decoder decoder = Base64.getDecoder();
            byte[] encryptedAesKey = decoder.decode(parts[0].trim());
            byte[] iv = decoder.decode(parts[1].trim());
            byte[] encryptedData = decoder.decode(parts[2].trim());

            // 1. 使用 RSA 私钥解密 AES 密钥
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
            SecretKeySpec aesKeySpec = new SecretKeySpec(aesKeyBytes, "AES");

            // 2. 使用解出的密钥解密数据
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKeySpec, ivSpec);
            byte[] decryptedBytes = aesCipher.doFinal(encryptedData);

            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("混合解密失败: " + e.getMessage());
            return "DecryptionError: " + e.getMessage();
        }
    }

    private String decryptRsaOnly(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] inputBytes = decoder.decode(encryptedText.trim());
            byte[] decryptedBytes = cipher.doFinal(inputBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("RSA 解密失败: " + e.getMessage());
            return "DecryptionError: " + e.getMessage();
        }
    }
}
