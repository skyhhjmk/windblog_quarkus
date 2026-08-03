package com.biliwind.blog.service.link;

import com.biliwind.blog.service.security.SecurityRateLimitService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;

@ApplicationScoped
public class LinkApplicationRateLimitService {

    private static final int CLIENT_LIMIT = 3;
    private static final int INGRESS_LIMIT = 300;
    private static final Duration WINDOW = Duration.ofHours(1);

    @Inject
    SecurityRateLimitService securityRateLimitService;

    public void checkAndRecord(String remoteAddress, String forwardedAddress) {
        String normalizedRemoteAddress = normalizeAddress(remoteAddress);
        String normalizedForwardedAddress = normalizeAddress(forwardedAddress);

        checkWindow("ingress:" + normalizedRemoteAddress, INGRESS_LIMIT);

        String clientAddress = normalizedRemoteAddress;
        if (!normalizedForwardedAddress.isBlank()) {
            clientAddress = normalizedForwardedAddress;
        }
        checkWindow("client:" + clientAddress, CLIENT_LIMIT);
    }

    private void checkWindow(String key, int maximumRequests) {
        if (!securityRateLimitService.tryAcquire("link-application:" + key, maximumRequests, WINDOW)) {
            throw new ClientErrorException(
                    "提交过于频繁，请一小时后再试",
                    Response.Status.TOO_MANY_REQUESTS
            );
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
}
