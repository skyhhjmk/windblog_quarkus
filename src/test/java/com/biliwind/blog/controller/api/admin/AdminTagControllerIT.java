package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * AdminTagController 集成测试
 * 测试标签管理接口
 */
@QuarkusIntegrationTest
class AdminTagControllerIT {

    @Test
    void shouldRejectUnauthorizedAccess() {
        given()
                .when().get("/api/admin/tags")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectInvalidToken() {
        given()
                .header("Authorization", "Bearer invalid_token")
                .when().get("/api/admin/tags")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedPost() {
        String requestBody = """
                {
                    "slug": "test-tag",
                    "name": {"zh-CN": "测试标签"},
                    "description": {"zh-CN": "测试描述"}
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when().post("/api/admin/tags")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedPut() {
        String requestBody = """
                {
                    "slug": "updated-tag",
                    "name": {"zh-CN": "更新标签"},
                    "description": {"zh-CN": "更新描述"}
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when().put("/api/admin/tags/1")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedDelete() {
        given()
                .when().delete("/api/admin/tags/1")
                .then()
                .statusCode(401);
    }
}
