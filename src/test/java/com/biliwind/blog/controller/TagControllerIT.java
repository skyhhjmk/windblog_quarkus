package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * TagController 集成测试
 * 测试标签相关端点
 */
@QuarkusIntegrationTest
class TagControllerIT {

    @Test
    void shouldReturnTagListPage() {
        given()
                .when().get("/tag")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnTagListWithPagination() {
        given()
                .when().get("/tag?page=1")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnTagListWithSearch() {
        given()
                .when().get("/tag?q=test")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestForTagList() {
        given()
                .header("X-PJAX", "true")
                .when().get("/tag")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnNotFoundForNonExistentTag() {
        given()
                .when().get("/tag/non-existent-tag-12345")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandleTagRequestWithHtmlExtension() {
        given()
                .when().get("/tag/non-existent.html")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandleTagDetailWithPagination() {
        given()
                .when().get("/tag/non-existent-tag?page=2")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandlePjaxRequestForTagDetail() {
        given()
                .header("X-PJAX", "true")
                .when().get("/tag/non-existent-tag")
                .then()
                .statusCode(404);
    }
}
