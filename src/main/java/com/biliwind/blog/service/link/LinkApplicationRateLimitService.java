package com.biliwind.blog.service.link;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class LinkApplicationRateLimitService {

    private static final long WINDOW_SECONDS = 3600;
    private static final int CLIENT_LIMIT = 3;
    private static final int INGRESS_LIMIT = 300;

    private final Map<String, RequestWindow> requestWindows = new ConcurrentHashMap<>();

    public void checkAndRecord(String remoteAddress, String forwardedAddress) {
        String normalizedRemoteAddress = normalizeAddress(remoteAddress);
        String normalizedForwardedAddress = normalizeAddress(forwardedAddress);

        checkWindow("ingress:" + normalizedRemoteAddress, INGRESS_LIMIT);

        String clientAddress = normalizedRemoteAddress;
        if (!normalizedForwardedAddress.isBlank()) {
            clientAddress = normalizedForwardedAddress;
        }
        checkWindow("client:" + clientAddress, CLIENT_LIMIT);
        removeExpiredWindows();
    }

    private synchronized void checkWindow(String key, int maximumRequests) {
        long currentEpochSecond = Instant.now().getEpochSecond();
        RequestWindow requestWindow = requestWindows.get(key);
        if (requestWindow == null || currentEpochSecond - requestWindow.startedAt >= WINDOW_SECONDS) {
            requestWindows.put(key, new RequestWindow(currentEpochSecond, 1));
            return;
        }
        if (requestWindow.requestCount >= maximumRequests) {
            throw new ClientErrorException(
                    "提交过于频繁，请一小时后再试",
                    Response.Status.TOO_MANY_REQUESTS
            );
        }
        requestWindow.requestCount = requestWindow.requestCount + 1;
    }

    private void removeExpiredWindows() {
        if (requestWindows.size() < 1000) {
            return;
        }
        long currentEpochSecond = Instant.now().getEpochSecond();
        for (Map.Entry<String, RequestWindow> entry : requestWindows.entrySet()) {
            RequestWindow requestWindow = entry.getValue();
            if (currentEpochSecond - requestWindow.startedAt >= WINDOW_SECONDS) {
                requestWindows.remove(entry.getKey(), requestWindow);
            }
        }
    }

    private String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String firstAddress = address.split(",")[0].trim();
        if (firstAddress.isBlank()) {
            return "unknown";
        }
        if (firstAddress.length() > 128) {
            return firstAddress.substring(0, 128);
        }
        return firstAddress;
    }

    private static class RequestWindow {
        private final long startedAt;
        private int requestCount;

        private RequestWindow(long startedAt, int requestCount) {
            this.startedAt = startedAt;
            this.requestCount = requestCount;
        }
    }
}
