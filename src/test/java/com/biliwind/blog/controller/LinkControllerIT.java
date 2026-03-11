package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * LinkController 集成测试
 * 测试链接相关端点
 */
@QuarkusIntegrationTest
class LinkControllerIT {

    @Test
    void shouldReturnLinkPage() {
        given()
                .when().get("/link")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestForLink() {
        given()
                .header("X-PJAX", "true")
                .when().get("/link")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldHandlePjaxRequestWithContainer() {
        given()
                .header("X-PJAX", "true")
                .header("X-PJAX-Container", "#custom-container")
                .when().get("/link")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

}