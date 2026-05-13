package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeRegion;

/**
 * 边缘节点更新请求
 */
public record EdgeNodeUpdateRequest(
        String name,
        String address,
        EdgeRegion region,
        EdgeConnectionType connectionType,
        Boolean isEnabled
) {
}
