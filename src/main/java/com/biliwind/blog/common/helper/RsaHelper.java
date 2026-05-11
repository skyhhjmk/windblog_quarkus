package com.biliwind.blog.common.helper;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.File;
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

    /**
     * 使用公钥加密文本
     */
    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] inputBytes = plainText.getBytes();
            byte[] encryptedBytes = cipher.doFinal(inputBytes);
            Base64.Encoder encoder = Base64.getEncoder();
            return encoder.encodeToString(encryptedBytes);
        } catch (Exception e) {
            LOGGER.error("RSA 加密失败: " + e.getMessage());
            return "EncryptionError: " + e.getMessage();
        }
    }

    /**
     * 使用私钥解密文本
     */
    public String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] inputBytes = decoder.decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(inputBytes);
            return new String(decryptedBytes);
        } catch (Exception e) {
            LOGGER.error("RSA 解密失败: " + e.getMessage());
            return "DecryptionError: " + e.getMessage();
        }
    }
}
