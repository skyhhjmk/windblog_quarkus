package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * AdminCategoryController 集成测试
 * 测试分类管理接口
 */
@QuarkusIntegrationTest
class AdminCategoryControllerIT {

    @Test
    void shouldRejectUnauthorizedAccess() {
        given()
                .when().get("/api/admin/categories")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectInvalidToken() {
        given()
                .header("Authorization", "Bearer invalid_token")
                .when().get("/api/admin/categories")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectUnauthorizedPost() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"slug\": \"test\", \"name\": {\"zh-CN\": \"测试\"}}")
                .when().post("/api/admin/categories")
                .then()
                .statusCode(401);
    }

}
