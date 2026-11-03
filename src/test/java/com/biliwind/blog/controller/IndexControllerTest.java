package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;

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
    void shouldAdvertiseAgentDiscoveryResources() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .header("Link", allOf(
                        containsString("</.well-known/api-catalog>; rel=\"api-catalog\""),
                        containsString("</api/public/openapi>; rel=\"service-desc\""),
                        containsString("</api/public/docs>; rel=\"service-doc\""),
                        containsString("</.well-known/api-catalog>; rel=\"describedby\"")));
    }

    @Test
    void shouldRedirectFirstPageToHome() {
        given().redirects().follow(false)
                .when().get("/page/1")
                .then()
                .statusCode(301)
                .header("Location", endsWith("/"));
    }

    @Test
    void shouldRedirectInvalidPageNumberToHome() {
        given().redirects().follow(false)
                .when().get("/page/0")
                .then()
                .statusCode(301)
                .header("Location", endsWith("/"));
    }
}
