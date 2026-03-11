package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * IndexController 集成测试
 * 使用 @QuarkusIntegrationTest 测试打包后的应用
 */
@QuarkusIntegrationTest
class IndexControllerIT {

    @Test
    void shouldReturnIndexPage() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }

    @Test
    void shouldReturnPageWithPagination() {
        given()
                .when().get("/page/1")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }
}
