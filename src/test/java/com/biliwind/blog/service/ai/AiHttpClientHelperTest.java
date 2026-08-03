package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AiHttpClientHelperTest {

    @Test
    void shouldRejectPrivateAiRequestBeforeOpeningConnection() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:11434/api/generate"))
                .GET()
                .build();

        assertThrows(IllegalArgumentException.class, () -> AiHttpClientHelper.sendAsync(
                HttpClient.newHttpClient(), request, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void shouldRejectNonHttpAiRequest() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.newBuilder()
                .uri(URI.create("file:///etc/passwd"))
                .GET()
                .build());
    }

    @Test
    void shouldRejectSpecialIpv4AndCredentialBearingAiRequests() {
        HttpRequest carrierGradeNatRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://100.64.0.1:11434/api/generate"))
                .GET()
                .build();
        assertThrows(IllegalArgumentException.class, () -> AiHttpClientHelper.sendAsync(
                HttpClient.newHttpClient(), carrierGradeNatRequest, HttpResponse.BodyHandlers.ofString()));

        HttpRequest credentialBearingRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://user:password@example.com/api"))
                .GET()
                .build();
        assertThrows(IllegalArgumentException.class, () -> AiHttpClientHelper.sendAsync(
                HttpClient.newHttpClient(), credentialBearingRequest, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void shouldRejectMappedIpv6PrivateAiRequest() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://[::ffff:127.0.0.1]:11434/api/generate"))
                .GET()
                .build();

        assertThrows(IllegalArgumentException.class, () -> AiHttpClientHelper.sendAsync(
                HttpClient.newHttpClient(), request, HttpResponse.BodyHandlers.ofString()));
    }
}
