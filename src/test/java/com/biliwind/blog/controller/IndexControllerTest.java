package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * IndexController 单元集成测试
 * 使用 @QuarkusTest 在测试 JVM 中启动应用
 */
@QuarkusTest
class IndexControllerTest {

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

    @Test
    void shouldHandleInvalidPageNumber() {
        given()
                .when().get("/page/0")
                .then()
                .statusCode(200)
                .contentType("text/html;charset=UTF-8");
    }
}
