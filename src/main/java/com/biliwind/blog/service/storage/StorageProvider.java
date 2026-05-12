package com.biliwind.blog.service.storage;

import java.io.InputStream;
import java.time.Duration;

public interface StorageProvider {

    String getName();

    void initialize(StorageProviderConfig config);

    boolean isAvailable();

    boolean supportsVariant(String mimeType, String variantType);

    String upload(InputStream data, String targetPath, String contentType) throws StorageException;

    void delete(String storagePath) throws StorageException;

    boolean exists(String storagePath);

    InputStream download(String storagePath) throws StorageException;

    String getSignedUrl(String storagePath, Duration expiration);

    String getPublicUrl(String storagePath);
}
