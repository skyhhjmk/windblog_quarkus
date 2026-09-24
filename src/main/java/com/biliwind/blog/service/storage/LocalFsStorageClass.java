package com.biliwind.blog.service.storage;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public class LocalFsStorageClass implements StorageClass {

    private static final Logger log = LoggerFactory.getLogger(LocalFsStorageClass.class);

    private String name;
    private Path rootPath;
    private Path encryptedBackupRoot;
    private String baseUrl;

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private java.util.List<String> supportedTypes;

    @Override
    public boolean isAvailable() {
        if (rootPath == null) {
            return false;
        }
        if (!Files.exists(rootPath)) {
            return false;
        }
        if (!Files.isWritable(rootPath)) {
            return false;
        }
        return true;
    }

    @Override
    public void initialize(StorageClassConfig config) {
        JsonNode json = config.getConfigJson();
        if (json != null && json.has("rootPath")) {
            this.rootPath = Paths.get(json.get("rootPath").asText());
        } else {
            this.rootPath = Paths.get("/tmp/windblog/uploads");
        }

        if (json != null && json.has("baseUrl")) {
            this.baseUrl = json.get("baseUrl").asText();
        } else {
            this.baseUrl = "/uploads";
        }
        this.rootPath = this.rootPath.toAbsolutePath().normalize();
        this.encryptedBackupRoot = this.rootPath.resolveSibling(
                this.rootPath.getFileName() + "-encrypted-backups").normalize();

        try {
            if (!Files.exists(this.rootPath)) {
                Files.createDirectories(this.rootPath);
            }
            Files.createDirectories(this.encryptedBackupRoot);
        } catch (Exception e) {
            throw new StorageException("Failed to initialize LocalFsStorageClass: " + e.getMessage(), e);
        }
        this.supportedTypes = config.getSupportedTypes();
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
            Path fullPath = resolvePath(targetPath);
            Path parentDir = fullPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.copy(data, fullPath, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(fullPath);
            long lastModified = Files.getLastModifiedTime(fullPath).toMillis();
            String etag = "\"" + size + "-" + lastModified + "\"";
            return targetPath;
        } catch (Exception e) {
            throw new StorageException("Failed to upload file to local FS", e);
        }
    }

    @Override
    public void delete(String storagePath) throws StorageException {
        try {
            Path fullPath = resolvePath(storagePath);
            Files.deleteIfExists(fullPath);
        } catch (Exception e) {
            throw new StorageException("Failed to delete file from local FS", e);
        }
    }

    @Override
    public boolean exists(String storagePath) {
        Path fullPath = resolvePath(storagePath);
        return Files.exists(fullPath);
    }

    @Override
    public InputStream download(String storagePath) throws StorageException {
        try {
            Path fullPath = resolvePath(storagePath);
            return Files.newInputStream(fullPath);
        } catch (Exception e) {
            throw new StorageException("Failed to download file from local FS", e);
        }
    }

    @Override
    public String getSignedUrl(String storagePath, Duration expiration) {
        return null;
    }

    @Override
    public String getPublicUrl(String storagePath) {
        if (storagePath != null && storagePath.startsWith("encrypted-backup/")) {
            return null;
        }
        String base = baseUrl;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String path = storagePath;
        if (storagePath.startsWith("/")) {
            path = storagePath.substring(1);
        }
        return base + path;
    }

    private Path resolvePath(String key) {
        if (key == null || key.startsWith("/") || key.contains("\\") || key.contains("..")) {
            throw new StorageException("Invalid storage path");
        }
        boolean encrypted = key.startsWith("encrypted-backup/");
        Path root = encrypted ? encryptedBackupRoot : rootPath;
        Path relative = Path.of(encrypted ? key.substring("encrypted-backup/".length()) : key);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new StorageException("Invalid storage path");
        }
        return resolved;
    }
}
