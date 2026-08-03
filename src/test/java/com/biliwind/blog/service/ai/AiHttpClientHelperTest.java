package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldBoundNonStreamingAiResponseBody() {
        HttpResponse.BodySubscriber<String> subscriber = AiHttpClientHelper
                .boundedStringBodyHandler(4).apply(null);
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap("12345".getBytes(StandardCharsets.UTF_8))));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertTrue(cancelled.get());
    }

    @Test
    void shouldDecodeBoundedNonStreamingAiResponseBody() {
        HttpResponse.BodySubscriber<String> subscriber = AiHttpClientHelper
                .boundedStringBodyHandler(4).apply(null);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
            }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertEquals("ok", subscriber.getBody().toCompletableFuture().join());
    }
}
