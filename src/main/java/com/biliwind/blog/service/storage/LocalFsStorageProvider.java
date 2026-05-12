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

public class LocalFsStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFsStorageProvider.class);

    private String name;
    private Path rootPath;
    private String baseUrl;

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void initialize(StorageProviderConfig config) {
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

        try {
            if (!Files.exists(this.rootPath)) {
                Files.createDirectories(this.rootPath);
            }
        } catch (Exception e) {
            throw new StorageException("Failed to initialize LocalFsStorageProvider: " + e.getMessage(), e);
        }
    }

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
    public boolean supportsVariant(String mimeType, String variantType) {
        return true;
    }

    @Override
    public String upload(InputStream data, String targetPath, String contentType) throws StorageException {
        try {
            Path fullPath = rootPath.resolve(targetPath);
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
            Path fullPath = rootPath.resolve(storagePath);
            Files.deleteIfExists(fullPath);
        } catch (Exception e) {
            throw new StorageException("Failed to delete file from local FS", e);
        }
    }

    @Override
    public boolean exists(String storagePath) {
        Path fullPath = rootPath.resolve(storagePath);
        return Files.exists(fullPath);
    }

    @Override
    public InputStream download(String storagePath) throws StorageException {
        try {
            Path fullPath = rootPath.resolve(storagePath);
            return Files.newInputStream(fullPath);
        } catch (Exception e) {
            throw new StorageException("Failed to download file from local FS", e);
        }
    }

    @Override
    public String getSignedUrl(String storagePath, Duration expiration) {
        return getPublicUrl(storagePath);
    }

    @Override
    public String getPublicUrl(String storagePath) {
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
}
