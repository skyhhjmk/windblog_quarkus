package com.biliwind.blog.service.inventory;

import java.util.Map;

public record UseRequest(Map<String, Object> data) {
    public UseRequest() { this(Map.of()); }
}
