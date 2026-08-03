package com.biliwind.blog.service.ai;

import com.biliwind.blog.service.security.ExternalHttpEndpointPolicy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.entity.AbstractBinAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.AbstractAsyncResponseConsumer;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpClient.Version;
import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Keeps the JDK HttpClient contract used by AI providers while pinning direct
 * connections to the addresses validated immediately before the request.
 */
final class AiPinnedHttpClient {

    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESPONSE_BODY_BYTES = 8 * 1024 * 1024;

    private final HttpClient jdkClient;
    private final CloseableHttpAsyncClient apacheClient;
    private final ValidatedDnsResolver dnsResolver;

    private AiPinnedHttpClient(HttpClient jdkClient,
                               CloseableHttpAsyncClient apacheClient,
                               ValidatedDnsResolver dnsResolver) {
        this.jdkClient = jdkClient;
        this.apacheClient = apacheClient;
        this.dnsResolver = dnsResolver;
    }

    static AiPinnedHttpClient direct(String pinnedHost, Predicate<String> privateHostAllowed) {
        ValidatedDnsResolver resolver = new ValidatedDnsResolver(pinnedHost, privateHostAllowed);
        CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(60))
                        .setResponseTimeout(Timeout.ofSeconds(60))
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
        client.start();
        return new AiPinnedHttpClient(null, client, resolver);
    }

    static AiPinnedHttpClient jdk(HttpClient client) {
        return new AiPinnedHttpClient(client, null, null);
    }

    boolean isDirect() {
        return apacheClient != null;
    }

    <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
            HttpRequest request, BodyHandler<T> bodyHandler) {
        if (jdkClient != null) {
            return jdkClient.sendAsync(request, bodyHandler);
        }

        try {
            URI uri = ExternalHttpEndpointPolicy.validateHttpUri(request.uri(), "AI endpoint");
            InetAddress[] addresses = resolveAndValidate(uri.getHost());
            dnsResolver.pin(uri.getHost(), addresses);
            SimpleHttpRequest apacheRequest = toApacheRequest(request);
            CompletableFuture<java.net.http.HttpResponse<T>> result = new CompletableFuture<>();
            Future<SimpleHttpResponse> requestFuture = apacheClient.execute(
                    SimpleRequestProducer.create(apacheRequest),
                    new LimitedResponseConsumer(MAX_RESPONSE_BODY_BYTES),
                    new FutureCallback<>() {
                        @Override
                        public void completed(SimpleHttpResponse response) {
                            try {
                                result.complete(adaptResponse(request, bodyHandler, response));
                            } catch (RuntimeException exception) {
                                result.completeExceptionally(exception);
                            }
                        }

                        @Override
                        public void failed(Exception exception) {
                            result.completeExceptionally(exception);
                        }

                        @Override
                        public void cancelled() {
                            result.cancel(false);
                        }
                    });
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    requestFuture.cancel(true);
                }
            });
            return result;
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private InetAddress[] resolveAndValidate(String host) {
        InetAddress[] addresses = ExternalHttpEndpointPolicy.resolveAddresses(host);
        for (InetAddress address : addresses) {
            if (!ExternalHttpEndpointPolicy.isPublicAddress(address)
                    && !dnsResolver.privateHostAllowed(host)) {
                throw new IllegalArgumentException("AI endpoint 不允许访问私有网络，请配置显式 allowlist");
            }
        }
        return addresses;
    }

    private static SimpleHttpRequest toApacheRequest(HttpRequest request) {
        SimpleHttpRequest apacheRequest = SimpleHttpRequest.create(request.method(), request.uri());
        for (Map.Entry<String, List<String>> entry : request.headers().map().entrySet()) {
            if ("host".equalsIgnoreCase(entry.getKey())
                    || "content-length".equalsIgnoreCase(entry.getKey())
                    || "transfer-encoding".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                apacheRequest.addHeader(entry.getKey(), value);
            }
        }
        Optional<HttpRequest.BodyPublisher> bodyPublisher = request.bodyPublisher();
        if (bodyPublisher.isPresent()) {
            byte[] body = collectBody(bodyPublisher.get());
            ContentType contentType = contentType(request.headers().firstValue("Content-Type").orElse(null));
            apacheRequest.setBody(body, contentType);
        }
        return apacheRequest;
    }

    private static byte[] collectBody(HttpRequest.BodyPublisher publisher) {
        CompletableFuture<byte[]> body = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                if (body.isDone()) {
                    return;
                }
                int remaining = buffer.remaining();
                if (remaining > MAX_REQUEST_BODY_BYTES - output.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalArgumentException("AI 请求体超过限制"));
                    return;
                }
                byte[] bytes = new byte[remaining];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                body.complete(output.toByteArray());
            }
        });
        return body.join();
    }

    private static ContentType contentType(String value) {
        if (value == null || value.isBlank()) {
            return ContentType.APPLICATION_OCTET_STREAM;
        }
        try {
            return ContentType.parse(value);
        } catch (RuntimeException exception) {
            return ContentType.APPLICATION_OCTET_STREAM;
        }
    }

    private static <T> java.net.http.HttpResponse<T> adaptResponse(
            HttpRequest request, BodyHandler<T> bodyHandler, SimpleHttpResponse response) {
        Header[] headers = response.getHeaders();
        HttpHeaders httpHeaders = HttpHeaders.of(
                Arrays.stream(headers).collect(java.util.stream.Collectors.groupingBy(
                        Header::getName,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(Header::getValue,
                                java.util.stream.Collectors.toList()))),
                (name, value) -> true);
        ResponseInfo info = new ResponseInfo() {
            @Override
            public int statusCode() {
                return response.getCode();
            }

            @Override
            public HttpHeaders headers() {
                return httpHeaders;
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }
        };
        BodySubscriber<T> subscriber = bodyHandler.apply(info);
        subscriber.onSubscribe(new SingleBufferSubscription());
        byte[] body = response.getBodyBytes();
        if (body != null && body.length > 0) {
            subscriber.onNext(List.of(ByteBuffer.wrap(body)));
        }
        subscriber.onComplete();
        T value = subscriber.getBody().toCompletableFuture().join();
        return new JdkHttpResponse<>(request, response, httpHeaders, value);
    }

    private static final class SingleBufferSubscription implements Flow.Subscription {
        @Override
        public void request(long count) {
        }

        @Override
        public void cancel() {
        }
    }

    private static final class JdkHttpResponse<T> implements java.net.http.HttpResponse<T> {
        private final HttpRequest request;
        private final SimpleHttpResponse response;
        private final HttpHeaders headers;
        private final T body;

        private JdkHttpResponse(HttpRequest request, SimpleHttpResponse response,
                                HttpHeaders headers, T body) {
            this.request = request;
            this.response = response;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return response.getCode();
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<java.net.http.HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    private static final class LimitedResponseConsumer
            extends AbstractAsyncResponseConsumer<SimpleHttpResponse, byte[]> {
        private LimitedResponseConsumer(int maxBytes) {
            super(new LimitedEntityConsumer(maxBytes));
        }

        @Override
        protected SimpleHttpResponse buildResult(HttpResponse response, byte[] body,
                                                  ContentType contentType) {
            SimpleHttpResponse result = SimpleHttpResponse.copy(response);
            result.setBody(body == null ? new byte[0] : body,
                    contentType == null ? ContentType.APPLICATION_OCTET_STREAM : contentType);
            return result;
        }

        @Override
        public void informationResponse(HttpResponse response,
                                         org.apache.hc.core5.http.protocol.HttpContext context) {
        }
    }

    private static final class LimitedEntityConsumer
            extends AbstractBinAsyncEntityConsumer<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private LimitedEntityConsumer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        protected void streamStart(ContentType contentType) {
        }

        @Override
        protected void data(ByteBuffer source, boolean endOfStream) throws IOException {
            if (source.remaining() > maxBytes - output.size()) {
                throw new IOException("AI 响应内容超过限制");
            }
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            output.write(bytes);
        }

        @Override
        protected byte[] generateContent() {
            return output.toByteArray();
        }

        @Override
        protected int capacityIncrement() {
            return 8192;
        }

        @Override
        public void releaseResources() {
            output.reset();
        }
    }

    static final class ValidatedDnsResolver implements DnsResolver {
        private final String pinnedHost;
        private final Predicate<String> privateHostAllowed;
        private final ConcurrentHashMap<String, InetAddress[]> pinnedAddresses = new ConcurrentHashMap<>();

        ValidatedDnsResolver(String pinnedHost, Predicate<String> privateHostAllowed) {
            this.pinnedHost = pinnedHost == null ? "" : pinnedHost.trim();
            this.privateHostAllowed = privateHostAllowed;
        }

        void pin(String host, InetAddress[] addresses) {
            if (!pinnedHost.isBlank() && !pinnedHost.equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("AI 请求主机与已验证 endpoint 不一致");
            }
            for (InetAddress address : addresses) {
                if (!ExternalHttpEndpointPolicy.isPublicAddress(address)
                        && !privateHostAllowed(host)) {
                    throw new IllegalArgumentException("AI endpoint 不允许访问私有网络，请配置显式 allowlist");
                }
            }
            pinnedAddresses.put(host.toLowerCase(), addresses.clone());
        }

        boolean privateHostAllowed(String host) {
            return privateHostAllowed != null && privateHostAllowed.test(host);
        }

        @Override
        public InetAddress[] resolve(String host) {
            if (!pinnedHost.isBlank() && !pinnedHost.equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("AI 请求主机与已验证 endpoint 不一致");
            }
            InetAddress[] addresses = pinnedAddresses.get(host.toLowerCase());
            if (addresses == null) {
                addresses = ExternalHttpEndpointPolicy.resolveAddresses(host);
                for (InetAddress address : addresses) {
                    if (!ExternalHttpEndpointPolicy.isPublicAddress(address)
                            && !privateHostAllowed(host)) {
                        throw new IllegalArgumentException("AI endpoint 不允许访问私有网络，请配置显式 allowlist");
                    }
                }
                pinnedAddresses.put(host.toLowerCase(), addresses.clone());
            }
            return addresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
