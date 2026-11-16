package com.biliwind.blog.controller;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class PublicToolsAndMcpTest {

    @Test
    void shouldExposePublicToolsRestApi() {
        given()
                .when().get("/api/public/tools")
                .then()
                .statusCode(200)
                .body("tools.size()", greaterThan(14))
                .body("mcpEndpoint", containsString("/api/public/mcp"));

        given()
                .queryParam("cidr", "192.168.1.10/24")
                .when().get("/api/public/tools/subnet")
                .then()
                .statusCode(200)
                .body("networkAddress", equalTo("192.168.1.0"))
                .body("broadcastAddress", equalTo("192.168.1.255"))
                .body("usableHosts", equalTo(254));

        given()
                .queryParam("value", "1700000000")
                .when().get("/api/public/tools/timestamp")
                .then()
                .statusCode(200)
                .body("epochSeconds", equalTo(1700000000))
                .body("isoUtc", equalTo("2023-11-14T22:13:20Z"));

        given()
                .queryParam("expr", "*/5 * * * *")
                .queryParam("count", 3)
                .when().get("/api/public/tools/cron")
                .then()
                .statusCode(200)
                .body("nextRuns", hasSize(3));

        given()
                .contentType("application/json")
                .body("{\"text\":\"hello\",\"algorithm\":\"SHA-256\"}")
                .when().post("/api/public/tools/hash")
                .then()
                .statusCode(200)
                .body("hex", equalTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));

        given()
                .contentType("application/json")
                .body("{\"text\":\"Hello WindBlog 你好世界\"}")
                .when().post("/api/public/tools/token-estimate")
                .then()
                .statusCode(200)
                .body("estimatedTokens", greaterThan(1))
                .body("cjkCharacters", equalTo(4));
    }

    @Test
    void shouldExposeStatelessMcpEndpoint() {
        given()
                .when().get("/api/public/mcp")
                .then()
                .statusCode(200)
                .body("protocol", equalTo("mcp-jsonrpc-2.0"));

        given()
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                .when().post("/api/public/mcp")
                .then()
                .statusCode(200)
                .body("result.serverInfo.name", equalTo("windblog-public-tools"));

        given()
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .when().post("/api/public/mcp")
                .then()
                .statusCode(200)
                .body("result.tools", hasSize(6));

        given()
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"subnet_calc\",\"arguments\":{\"cidr\":\"10.0.0.5/24\"}}}")
                .when().post("/api/public/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", equalTo(false))
                .body("result.content[0].text", containsString("10.0.0.0"));
    }

    @Test
    void shouldIncludeAllToolsInSitemapPages() {
        given()
                .when().get("/sitemap-pages.xml")
                .then()
                .statusCode(200)
                .body(containsString("/tools/ai-token"))
                .body(containsString("/tools/jwt"))
                .body(containsString("/tools/jsonl-to-table"))
                .body(containsString("/tools/file-transfer"))
                .body(containsString("/tools/image-generator"));
    }
}
