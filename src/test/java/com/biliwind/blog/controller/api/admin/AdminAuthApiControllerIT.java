package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * AdminAuthApiController 集成测试
 */
@QuarkusIntegrationTest
class AdminAuthApiControllerIT {

    @Test
    void shouldLoginWithValidCredentials() {
        String loginRequest = """
                {
                    "username": "admin",
                    "password": "admin"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(loginRequest)
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(200)
                .body("token", notNullValue());
    }

    @Test
    void shouldRejectInvalidCredentials() {
        String loginRequest = """
                {
                    "username": "admin",
                    "password": "wrongpassword"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(loginRequest)
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(401);
    }
}
