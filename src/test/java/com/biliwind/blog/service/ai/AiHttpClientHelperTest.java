package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiHttpClientHelperTest {

    @Test
    void shouldKeepResolvedAiAddressesPinnedPerHost() throws Exception {
        AiPinnedHttpClient.PinnedDnsResolver resolver = new AiPinnedHttpClient.PinnedDnsResolver();
        InetAddress address = InetAddress.getByAddress(
                "example.com", new byte[]{93, (byte) 184, (byte) 216, 34});
        InetAddress otherAddress = InetAddress.getByName("127.0.0.1");

        resolver.pin("example.com", new InetAddress[]{address});
        resolver.pin("other.example", new InetAddress[]{otherAddress});

        assertArrayEquals(new InetAddress[]{address}, resolver.resolve("example.com"));
        assertArrayEquals(new InetAddress[]{otherAddress}, resolver.resolve("other.example"));
    }

    @Test
    void shouldAllowPrivateAiAddressWhenPinning() throws Exception {
        AiPinnedHttpClient.PinnedDnsResolver resolver = new AiPinnedHttpClient.PinnedDnsResolver();
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");

        resolver.pin("example.com", new InetAddress[]{privateAddress});

        assertArrayEquals(new InetAddress[]{privateAddress}, resolver.resolve("example.com"));
    }

    @Test
    void shouldRejectNonHttpAiRequest() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.newBuilder()
                .uri(URI.create("file:///etc/passwd"))
                .GET()
                .build());
    }

    @Test
    void shouldRejectCredentialBearingAiRequests() {
        HttpRequest credentialBearingRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://user:password@example.com/api"))
                .GET()
                .build();
        assertThrows(IllegalArgumentException.class, () -> AiHttpClientHelper.sendAsync(
                HttpClient.newHttpClient(), credentialBearingRequest, HttpResponse.BodyHandlers.ofString()));
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

    @Test
    void shouldRejectStreamingResponseAfterBudgetIsExceeded() {
        AtomicInteger byteCounter = new AtomicInteger(8 * 1024 * 1024);

        assertThrows(IllegalArgumentException.class,
                () -> AiHttpClientHelper.consumeStreamingResponseBudget(byteCounter, "x"));
    }
}
