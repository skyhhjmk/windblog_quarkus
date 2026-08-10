package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

@ApplicationScoped
public class StorageConfigProtector {

    private static final String ENCRYPTED_PREFIX = "enc:v1:";
    private static final String ENV_REFERENCE_PREFIX = "${env:";
    private static final String SECRET_ENV_NAME = "WINDBLOG_STORAGE_CONFIG_SECRET";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile SecureRandom secureRandom;

    public String protectForStorage(String configJson) {
        JsonNode rootNode = parseConfig(configJson);
        JsonNode protectedNode = protectNode(rootNode);
        return protectedNode.toString();
    }

    public String protectForStorage(String configJson, String existingConfigJson) {
        JsonNode rootNode = parseConfig(configJson);
        JsonNode existingRootNode = parseConfig(existingConfigJson);
        JsonNode protectedNode = protectNode(rootNode, existingRootNode);
        return protectedNode.toString();
    }

    public String revealForRuntime(String configJson) {
        JsonNode rootNode = parseConfig(configJson);
        JsonNode revealedNode = revealNode(rootNode);
        return revealedNode.toString();
    }

    public String maskForResponse(String configJson) {
        JsonNode rootNode = parseConfig(configJson);
        JsonNode maskedNode = maskNode(rootNode);
        return maskedNode.toString();
    }

    private JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid configJson format", e);
        }
    }

    private JsonNode protectNode(JsonNode node) {
        if (node == null) {
            return objectMapper.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode copiedObject = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childNode = field.getValue();
                if (isSensitiveField(field.getKey()) && childNode != null && childNode.isTextual()) {
                    copiedObject.set(field.getKey(), new TextNode(protectSensitiveValue(childNode.asText())));
                } else {
                    copiedObject.set(field.getKey(), protectNode(childNode));
                }
            }
            return copiedObject;
        }
        if (node.isArray()) {
            ArrayNode copiedArray = objectMapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                copiedArray.add(protectNode(node.get(index)));
            }
            return copiedArray;
        }
        return node.deepCopy();
    }

    private JsonNode protectNode(JsonNode node, JsonNode existingNode) {
        if (node == null) {
            return objectMapper.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode copiedObject = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childNode = field.getValue();
                JsonNode existingChildNode = null;
                if (existingNode != null && existingNode.isObject()) {
                    existingChildNode = existingNode.get(field.getKey());
                }
                if (isSensitiveField(field.getKey()) && childNode != null && childNode.isTextual()) {
                    String requestedValue = childNode.asText();
                    if (isMaskedValue(requestedValue) && existingChildNode != null) {
                        copiedObject.set(field.getKey(), existingChildNode.deepCopy());
                    } else {
                        copiedObject.set(field.getKey(), new TextNode(protectSensitiveValue(requestedValue)));
                    }
                } else {
                    copiedObject.set(field.getKey(), protectNode(childNode, existingChildNode));
                }
            }
            return copiedObject;
        }
        if (node.isArray()) {
            ArrayNode copiedArray = objectMapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                JsonNode existingChildNode = null;
                if (existingNode != null && existingNode.isArray() && existingNode.size() > index) {
                    existingChildNode = existingNode.get(index);
                }
                copiedArray.add(protectNode(node.get(index), existingChildNode));
            }
            return copiedArray;
        }
        return node.deepCopy();
    }

    private JsonNode revealNode(JsonNode node) {
        if (node == null) {
            return objectMapper.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode copiedObject = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childNode = field.getValue();
                if (childNode != null && childNode.isTextual() && isEncryptedValue(childNode.asText())) {
                    copiedObject.set(field.getKey(), new TextNode(decryptValue(childNode.asText())));
                } else {
                    copiedObject.set(field.getKey(), revealNode(childNode));
                }
            }
            return copiedObject;
        }
        if (node.isArray()) {
            ArrayNode copiedArray = objectMapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                copiedArray.add(revealNode(node.get(index)));
            }
            return copiedArray;
        }
        return node.deepCopy();
    }

    private JsonNode maskNode(JsonNode node) {
        if (node == null) {
            return objectMapper.createObjectNode();
        }
        if (node.isObject()) {
            ObjectNode copiedObject = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childNode = field.getValue();
                if (isSensitiveField(field.getKey()) && childNode != null && childNode.isTextual()) {
                    copiedObject.set(field.getKey(), new TextNode(maskSensitiveValue(childNode.asText())));
                } else {
                    copiedObject.set(field.getKey(), maskNode(childNode));
                }
            }
            return copiedObject;
        }
        if (node.isArray()) {
            ArrayNode copiedArray = objectMapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                copiedArray.add(maskNode(node.get(index)));
            }
            return copiedArray;
        }
        return node.deepCopy();
    }

    private String protectSensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (isEncryptedValue(value) || isEnvironmentReference(value)) {
            return value;
        }
        return encryptValue(value);
    }

    private String maskSensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (isEnvironmentReference(value)) {
            return value;
        }
        if (isEncryptedValue(value)) {
            return "******";
        }
        if (value.length() <= 4) {
            return "******";
        }
        return value.substring(0, 4) + "******";
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalizedFieldName = fieldName.toLowerCase();
        return normalizedFieldName.contains("secret")
                || normalizedFieldName.contains("password")
                || normalizedFieldName.contains("token")
                || normalizedFieldName.contains("accesskey");
    }

    private boolean isEnvironmentReference(String value) {
        return value.startsWith(ENV_REFERENCE_PREFIX) && value.endsWith("}");
    }

    private boolean isEncryptedValue(String value) {
        return value.startsWith(ENCRYPTED_PREFIX);
    }

    private boolean isMaskedValue(String value) {
        if (value == null) {
            return false;
        }
        return "******".equals(value) || value.endsWith("******");
    }

    private String encryptValue(String value) {
        try {
            byte[] initializationVector = new byte[GCM_IV_BYTES];
            getSecureRandom().nextBytes(initializationVector);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_BITS, initializationVector);
            cipher.init(Cipher.ENCRYPT_MODE, buildSecretKey(), parameterSpec);
            byte[] encryptedBytes = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String encodedIv = Base64.getEncoder().encodeToString(initializationVector);
            String encodedCipherText = Base64.getEncoder().encodeToString(encryptedBytes);
            return ENCRYPTED_PREFIX + encodedIv + ":" + encodedCipherText;
        } catch (Exception e) {
            throw new IllegalStateException("存储配置敏感字段加密失败", e);
        }
    }

    private String decryptValue(String value) {
        try {
            String encryptedPayload = value.substring(ENCRYPTED_PREFIX.length());
            String[] payloadParts = encryptedPayload.split(":", 2);
            if (payloadParts.length != 2) {
                throw new IllegalArgumentException("密文格式不正确");
            }
            byte[] initializationVector = Base64.getDecoder().decode(payloadParts[0]);
            byte[] encryptedBytes = Base64.getDecoder().decode(payloadParts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_BITS, initializationVector);
            cipher.init(Cipher.DECRYPT_MODE, buildSecretKey(), parameterSpec);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("存储配置敏感字段解密失败", e);
        }
    }

    private SecretKeySpec buildSecretKey() {
        String secret = System.getenv(SECRET_ENV_NAME);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + SECRET_ENV_NAME);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("存储配置密钥初始化失败", e);
        }
    }

    private SecureRandom getSecureRandom() {
        SecureRandom cached = secureRandom;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = secureRandom;
            if (cached == null) {
                cached = new SecureRandom();
                secureRandom = cached;
            }
            return cached;
        }
    }
}
