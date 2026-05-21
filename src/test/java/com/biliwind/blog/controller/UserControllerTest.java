package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class UserControllerTest {

    @Test
    void shouldFallbackExternalRedirectToHome() {
        given()
                .queryParam("redirect", "https://evil.example")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/\""));
    }

    @Test
    void shouldKeepInternalRedirectPath() {
        given()
                .queryParam("redirect", "/posts/demo")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/posts/demo\""));
    }

    @Test
    void shouldFallbackProtocolRelativeRedirectToHome() {
        given()
                .queryParam("redirect", "//evil.example")
                .when()
                .get("/user/login")
                .then()
                .statusCode(200)
                .body(containsString("name=\"redirect\" value=\"/\""));
    }
}
