package com.biliwind.blog.service.storage;

public record UploadResult(
        String path,
        long size,
        String etag
) {
}
