package com.biliwind.blog.common.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeCatalogTest {

    @Test
    void shouldUseOneRegistrationConflictCodeWithoutRevealingTheField() {
        assertEquals("WB-AUTH-REGISTER-009",
                ErrorCodeCatalog.resolve("/user/api/register", 409, "用户名或邮箱已被使用，请更换后重试"));
        assertEquals("WB-AUTH-REGISTER-007",
                ErrorCodeCatalog.resolve("/user/api/register", 400, "邮箱格式不正确"));
        // Historical messages stay mapped so old clients and logs keep their codes.
        assertEquals("WB-AUTH-REGISTER-004",
                ErrorCodeCatalog.resolve("/user/api/register", 409, "用户名已被使用"));
        assertEquals("WB-AUTH-REGISTER-005",
                ErrorCodeCatalog.resolve("/user/api/register", 409, "邮箱已被注册"));
    }

    @Test
    void shouldMapUnknownRegionRejectionToItsOwnCode() {
        assertEquals("WB-REGION-001",
                ErrorCodeCatalog.resolve("/api/admin/posts", 400, "未知的区域代码：kr"));
    }
}
