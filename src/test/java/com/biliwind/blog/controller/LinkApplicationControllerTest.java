package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LinkApplicationControllerTest {

    @Test
    void shouldRenderLinkApplicationForm() {
        given()
                .when()
                .get("/link/apply")
                .then()
                .statusCode(200)
                .body(containsString("linkApplicationForm"));
    }

    @Test
    void shouldRequireCsrfToken() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "测试站点",
                          "url": "file:///etc/passwd",
                          "email": "owner@example.com",
                          "placementType": "HOME_PAGE",
                          "placementUrl": "https://example.com"
                        }
                        """)
                .when()
                .post("/api/link-applications")
                .then()
                .statusCode(403);
    }
}
