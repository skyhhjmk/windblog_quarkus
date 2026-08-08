package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * AdminPostApiController 集成测试
 * 测试文章管理接口
 */
@QuarkusIntegrationTest
class AdminPostApiControllerIT {

    @Test
    void shouldRejectUnauthorizedAccess() {
        given()
                .when().get("/api/admin/posts")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectInvalidToken() {
        given()
                .header("Authorization", "Bearer invalid_token")
                .when().get("/api/admin/posts")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedGetById() {
        given()
                .when().get("/api/admin/posts/1")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedPost() {
        String requestBody = """
                {
                    "slug": "test-post",
                    "title": {"zh-CN": "测试文章"},
                    "contentMarkdown": {"zh-CN": "测试内容"},
                    "status": 0
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when().post("/api/admin/posts")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedPut() {
        String requestBody = """
                {
                    "slug": "updated-post",
                    "title": {"zh-CN": "更新文章"},
                    "version": 1
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when().put("/api/admin/posts/1")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedDelete() {
        given()
                .when().delete("/api/admin/posts/1")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedTranslation() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"sourceLanguage\":\"zh-cn\",\"targetLanguage\":\"en-us\","
                        + "\"title\":\"标题\",\"summary\":\"摘要\",\"contentMarkdown\":\"正文\"}")
                .when().post("/api/admin/posts/1/translation")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedAccessWithQueryParams() {
        given()
                .when().get("/api/admin/posts?page=1&pageSize=10&status=1")
                .then()
                .statusCode(401);
    }
}
