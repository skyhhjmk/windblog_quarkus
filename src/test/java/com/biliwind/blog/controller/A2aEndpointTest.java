package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
class A2aEndpointTest {

    private static final String NO_RESULT_QUERY = "zz-a2a-no-result-fixture";

    private static String requestBody() {
        return "{\"message\":{\"messageId\":\"a2a-test-1\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"" + NO_RESULT_QUERY + "\"}]}}";
    }

    @Test
    void publishesAgentCardWithA2aInterface() {
        given()
                .when().get("/.well-known/agent-card.json")
                .then()
                .statusCode(200)
                .body("supportedInterfaces[0].url", endsWith("/a2a"));
    }

    @Test
    void acceptsA2aJsonAndReturnsAgentMessageWithContextId() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/a2a+json")
                .header("A2A-Version", "1.0")
                .body(requestBody())
                .when().post("/a2a/message:send")
                .then()
                .statusCode(200)
                .body("message.role", equalTo("ROLE_AGENT"))
                .body("message.contextId", not(blankOrNullString()));
    }

    @Test
    void acceptsPlainJsonAcceptHeader() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/a2a+json")
                .accept("application/json")
                .header("A2A-Version", "1.0")
                .body(requestBody())
                .when().post("/a2a/message:send")
                .then()
                .statusCode(200)
                .body("message.role", equalTo("ROLE_AGENT"));
    }

    @Test
    void rejectsRequestsWithoutProtocolVersion() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/a2a+json")
                .body(requestBody())
                .when().post("/a2a/message:send")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }
}
