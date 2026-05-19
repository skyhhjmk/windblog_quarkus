package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.EdgeConnectionType;

/**
 * 边缘节点更新请求
 */
public record EdgeNodeUpdateRequest(
        String name,
        String externalUrl,
        String apiUrl,
        String grpcAddress,
        BlogRegion region,
        EdgeConnectionType connectionType,
        Boolean isEnabled,
        Integer edgeGrpcPort
) {
}
