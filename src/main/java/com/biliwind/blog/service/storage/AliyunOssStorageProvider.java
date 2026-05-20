package com.biliwind.blog.service.storage;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OSSClientBuilder;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.Credentials;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.models.*;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AliyunOssStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunOssStorageProvider.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{env:([^}]+)\\}");
    private String name;
    private OSSClient ossClient;
    private String endpoint;
    private String bucketName;
    private String basePath;
    private String cdnDomain;
    private boolean cdnEnabled;
    private boolean useHttps;
    private String region;
    private String storedAccessKeyId;
    private String storedAccessKeySecret;

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String resolveEnvVars(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = System.getenv(envName);
            if (envValue == null) {
                log.warn("Environment variable {} not found", envName);
                envValue = "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String normalizeEndpoint(String rawEndpoint, boolean useHttpsFlag) {
        String result = rawEndpoint;
        if (result == null || result.isEmpty()) {
            return null;
        }
        if (result.startsWith("http://") || result.startsWith("https://")) {
            return result;
        }
        if (useHttpsFlag) {
            return "https://" + result;
        }
        return "http://" + result;
    }

    private String buildFullPath(String targetPath) {
        if (basePath == null || basePath.isEmpty()) {
            if (targetPath.startsWith("/")) {
                return targetPath.substring(1);
            }
            return targetPath;
        }
        String normalizedTarget = targetPath;
        if (targetPath.startsWith("/")) {
            normalizedTarget = targetPath.substring(1);
        }
        String separator = "/";
        if (basePath.endsWith("/")) {
            separator = "";
        }
        return basePath + separator + normalizedTarget;
    }

    private String stripLeadingSlash(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }

    private java.util.List<String> supportedTypes;

    @Override
    public void initialize(StorageProviderConfig config) {
        JsonNode json = config.getConfigJson();
        if (json == null) {
            throw new StorageException("Config JSON is missing for AliyunOssStorageProvider");
        }

        this.region = resolveEnvVars(json.path("region").asText("cn-hangzhou"));
        this.endpoint = resolveEnvVars(json.path("endpoint").asText(null));
        this.bucketName = resolveEnvVars(json.path("bucketName").asText(null));
        String accessKeyId = resolveEnvVars(json.path("accessKeyId").asText(null));
        String accessKeySecret = resolveEnvVars(json.path("accessKeySecret").asText(null));
        this.basePath = json.path("basePath").asText("");
        this.useHttps = json.path("useHttps").asBoolean(true);
        this.cdnDomain = config.getCdnDomain();
        this.cdnEnabled = config.isCdnEnabled();

        if (this.bucketName == null || accessKeyId == null || accessKeySecret == null) {
            throw new StorageException("Missing required configuration for AliyunOssStorageProvider");
        }

        if (this.region == null || this.region.isEmpty()) {
            this.region = "cn-hangzhou";
        }

        this.storedAccessKeyId = accessKeyId;
        this.storedAccessKeySecret = accessKeySecret;
        CredentialsProvider credentialsProvider = new CustomCredentialsProvider();

        OSSClientBuilder clientBuilder = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(this.region);

        if (this.endpoint != null && !this.endpoint.isEmpty()) {
            this.endpoint = normalizeEndpoint(this.endpoint, this.useHttps);
            clientBuilder.endpoint(this.endpoint);
        }

        if (!this.useHttps) {
            clientBuilder.disableSsl(true);
        }

        this.ossClient = clientBuilder.build();
        this.supportedTypes = config.getSupportedTypes();
    }

    @Override
    public boolean isAvailable() {
        try {
            ListObjectsRequest request = ListObjectsRequest.newBuilder()
                    .bucket(this.bucketName)
                    .maxKeys(Long.valueOf(1L))
                    .build();
            ossClient.listObjects(request);
            return true;
        } catch (Exception e) {
            log.error("Aliyun OSS is not available", e);
            return false;
        }
    }

    @Override
    public boolean supportsVariant(String mimeType, String variantType) {
        if (supportedTypes == null || supportedTypes.isEmpty() || supportedTypes.contains("*")) {
            return true;
        }
        for (String type : supportedTypes) {
            if (type.equals(mimeType)) {
                return true;
            }
            if (type.endsWith("/*")) {
                String prefix = type.substring(0, type.length() - 2);
                if (mimeType.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String upload(InputStream data, String targetPath, String contentType) throws StorageException {
        try {
            String fullPath = buildFullPath(targetPath);
            fullPath = stripLeadingSlash(fullPath);

            BinaryData bodyData = BinaryData.fromStream(data);

            PutObjectRequest.Builder requestBuilder = PutObjectRequest.newBuilder()
                    .bucket(this.bucketName)
                    .key(fullPath)
                    .body(bodyData);

            if (contentType != null && !contentType.isEmpty()) {
                requestBuilder.contentType(contentType);
            }

            PutObjectRequest request = requestBuilder.build();
            ossClient.putObject(request);
            return fullPath;
        } catch (Exception e) {
            throw new StorageException("Failed to upload to Aliyun OSS", e);
        }
    }

    @Override
    public void delete(String storagePath) throws StorageException {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.newBuilder()
                    .bucket(this.bucketName)
                    .key(storagePath)
                    .build();
            ossClient.deleteObject(request);
        } catch (Exception e) {
            throw new StorageException("Failed to delete from Aliyun OSS", e);
        }
    }

    @Override
    public boolean exists(String storagePath) {
        try {
            HeadObjectRequest request = HeadObjectRequest.newBuilder()
                    .bucket(this.bucketName)
                    .key(storagePath)
                    .build();
            ossClient.headObject(request);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public InputStream download(String storagePath) throws StorageException {
        try {
            GetObjectRequest request = GetObjectRequest.newBuilder()
                    .bucket(this.bucketName)
                    .key(storagePath)
                    .build();
            GetObjectResult result = ossClient.getObject(request);
            return result.body();
        } catch (Exception e) {
            throw new StorageException("Failed to download from Aliyun OSS", e);
        }
    }

    @Override
    public String getSignedUrl(String storagePath, Duration expiration) {
        try {
            GetObjectRequest request = GetObjectRequest.newBuilder()
                    .bucket(this.bucketName)
                    .key(storagePath)
                    .build();

            PresignOptions options = PresignOptions.newBuilder()
                    .expiration(expiration)
                    .build();

            PresignResult result = ossClient.presign(request, options);
            return result.url();
        } catch (Exception e) {
            log.error("Failed to generate signed URL from Aliyun OSS", e);
            return getPublicUrl(storagePath);
        }
    }

    @Override
    public String getPublicUrl(String storagePath) {
        if (cdnEnabled && cdnDomain != null && !cdnDomain.isEmpty()) {
            return "https://" + cdnDomain + "/" + storagePath;
        }

        String host = endpoint;
        if (host == null || host.isEmpty()) {
            host = bucketName + ".oss-" + region + ".aliyuncs.com";
        } else {
            if (host.startsWith("https://")) {
                host = host.substring(8);
            }
            if (host.startsWith("http://")) {
                host = host.substring(7);
            }
        }

        String protocol = "http://";
        if (useHttps) {
            protocol = "https://";
        }
        return protocol + bucketName + "." + host + "/" + storagePath;
    }

    private class CustomCredentialsProvider implements CredentialsProvider {
        @Override
        public Credentials getCredentials() {
            return new Credentials(storedAccessKeyId, storedAccessKeySecret);
        }
    }
}
