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

    @Test
    void shouldRenderPasswordRecoveryPage() {
        given()
                .when()
                .get("/user/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("找回密码"))
                .body(containsString("forgotPasswordForm"));
    }

    @Test
    void shouldRenderExpiredPasswordResetPageWithoutToken() {
        given()
                .when()
                .get("/user/reset-password")
                .then()
                .statusCode(200)
                .body(containsString("链接无效或已过期"));
    }

    @Test
    void shouldRenderLegalPages() {
        given()
                .when()
                .get("/terms")
                .then()
                .statusCode(200)
                .body(containsString("用户协议"));

        given()
                .when()
                .get("/privacy")
                .then()
                .statusCode(200)
                .body(containsString("隐私政策"));
    }
}
