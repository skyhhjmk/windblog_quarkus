package com.biliwind.blog.service.edge;

import java.util.Map;

public class RoutedHttpExchange {

    public record Request(
            String method,
            String path,
            String query,
            Map<String, String> headers,
            byte[] body) {
    }

    public record Response(
            int status,
            Map<String, String> headers,
            byte[] body,
            String errorMessage) {
    }
}
