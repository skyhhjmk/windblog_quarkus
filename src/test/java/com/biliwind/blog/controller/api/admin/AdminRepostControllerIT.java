package com.biliwind.blog.controller.api.admin;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusIntegrationTest
class AdminRepostControllerIT {

    @Test
    void shouldRejectUnauthenticatedPolicyCatalogAccess() {
        given()
                .when().get("/api/admin/repost/policies")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldReturnBuiltInPolicyCatalogToAdministrator() {
        String token = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "account": "admin",
                            "password": "admin"
                        }
                        """)
                .when()
                .post("/api/admin/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .path("token");

        given()
                .header("Authorization", "Bearer " + token)
                .when().get("/api/admin/repost/policies")
                .then()
                .statusCode(200)
                .body("size()", equalTo(8))
                .body("[0].code", equalTo("REQUEST_REQUIRED"))
                .body("[0].requiresApplication", equalTo(true))
                .body("[1].licenseUrl", notNullValue())
                .body("[3].conditions", notNullValue());
    }
}
