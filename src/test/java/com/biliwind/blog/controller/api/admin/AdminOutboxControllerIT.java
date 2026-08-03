package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusIntegrationTest
class AdminOutboxControllerIT {

    @Test
    void shouldRejectUnauthenticatedOutboxAccess() {
        given()
                .when().get("/api/admin/outbox")
                .then().statusCode(401);

        given()
                .when().post("/api/admin/outbox/1/replay")
                .then().statusCode(401);
    }

    @Test
    void shouldAllowAdministratorToReadSafeOutboxMetadata() {
        String loginRequest = """
                {
                    "account": "admin",
                    "password": "admin"
                }
                """;
        String token = given()
                .contentType(ContentType.JSON)
                .body(loginRequest)
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given()
                .header("Authorization", "Bearer " + token)
                .queryParam("pageSize", 1)
                .when().get("/api/admin/outbox")
                .then()
                .statusCode(200)
                .body("items", notNullValue())
                .body("total", notNullValue());
    }
}
