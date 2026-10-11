package com.biliwind.blog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiCatalogResourceTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void shouldPublishRfc9727Linkset() throws IOException {
        String contentType = given()
                .when().get("/.well-known/api-catalog")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.containsString("application/linkset+json"))
                .extract().header("Content-Type");
        assertTrue(contentType.contains("https://www.rfc-editor.org/info/rfc9727"));

        JsonNode catalog = objectMapper.readTree(given()
                .when().get("/.well-known/api-catalog")
                .then().statusCode(200)
                .extract().asString());
        JsonNode entries = catalog.path("linkset");
        assertTrue(entries.isArray());
        assertEquals(1, entries.size());

        JsonNode entry = entries.get(0);
        String anchor = entry.path("anchor").asText();
        assertTrue(anchor.startsWith("http://") || anchor.startsWith("https://"));
        assertEquals("application/vnd.oai.openapi+json;version=3.1",
                entry.path("service-desc").get(0).path("type").asText());
        assertEquals("text/html", entry.path("service-doc").get(0).path("type").asText());
        assertTrue(entry.path("service-desc").get(0).path("href").asText().startsWith(anchor.substring(0, anchor.indexOf('/', anchor.indexOf("//") + 2))));
        assertTrue(entry.path("service-doc").get(0).path("href").asText().endsWith("/api/public/docs"));
        assertTrue(entry.path("status").get(0).path("href").asText().endsWith("/q/health/ready"));
    }

    @Test
    void shouldAdvertiseCatalogInHeadResponse() {
        given()
                .when().head("/.well-known/api-catalog")
                .then()
                .statusCode(200)
                .header("Link", org.hamcrest.Matchers.containsString("rel=\"api-catalog\""))
                .header("Content-Type", org.hamcrest.Matchers.containsString("application/linkset+json"));
    }

    @Test
    void shouldExposePublicOnlyOpenApiAndHumanDocs() throws IOException {
        String openApi = given()
                .when().get("/api/public/openapi")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.containsString("application/vnd.oai.openapi+json"))
                .extract().asString();
        JsonNode specification = objectMapper.readTree(openApi);
        assertEquals("3.1.0", specification.path("openapi").asText());
        assertTrue(specification.path("paths").has("/search/suggest"));
        assertTrue(specification.path("paths").has("/api/comments/post/{slug}"));
        assertTrue(specification.path("paths").has("/api/link-applications"));
        assertFalse(specification.path("paths").has("/api/admin/auth/login"));
        assertFalse(specification.path("paths").has("/api/internal/integrations/codex-creator/content"));
        assertFalse(specification.path("paths").has("/api/user/wallet/info"));

        given()
                .when().get("/api/public/docs")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.containsString("text/html"))
                .body(org.hamcrest.Matchers.containsString("WindBlog 公共 API"));
    }
}
