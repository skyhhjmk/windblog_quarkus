package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * PostController 集成测试
 * 测试文章相关端点
 */
@QuarkusIntegrationTest
class PostControllerIT {

    @Test
    void shouldReturnNotFoundForNonExistentPost() {
        given()
                .when().get("/post/non-existent-post-12345")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldReturnNotFoundForNonExistentPostWithLang() {
        given()
                .when().get("/en/post/non-existent-post-12345")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandlePostRequestWithHtmlExtension() {
        given()
                .when().get("/post/non-existent.html")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandlePjaxRequest() {
        given()
                .header("X-PJAX", "true")
                .when().get("/post/non-existent-post")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandlePjaxRequestWithContainer() {
        given()
                .header("X-PJAX", "true")
                .header("X-PJAX-Container", "#custom-container")
                .when().get("/post/non-existent-post")
                .then()
                .statusCode(404);
    }

    @Test
    void shouldHandleRequestWithDifferentLanguages() {
        given()
                .when().get("/en/post/non-existent")
                .then()
                .statusCode(404);

        given()
                .when().get("/zh/post/non-existent")
                .then()
                .statusCode(404);
    }
}
