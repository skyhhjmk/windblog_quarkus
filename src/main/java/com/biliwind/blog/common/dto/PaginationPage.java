package com.biliwind.blog.common.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A rendered page link for the public pagination control.
 */
@RegisterForReflection
public record PaginationPage(int number, String url, boolean current) {
}
