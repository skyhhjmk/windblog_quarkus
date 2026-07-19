package com.biliwind.blog.service.security;

import com.biliwind.blog.service.ConfigManager;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ClientIpResolver {

    private static final String CONFIG_KEY = "security_network";
    private static final String DEFAULT_TRUSTED_CIDRS = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    @Inject
    ConfigManager configManager;

    public ClientIpResolution resolve(RoutingContext routingContext) {
        String remoteAddress = "unknown";
        String headerValue = null;
        if (routingContext != null && routingContext.request() != null) {
            remoteAddress = routingContext.request().remoteAddress().hostAddress();
            headerValue = routingContext.request().getHeader(resolveHeaderName());
        }
        return resolve(remoteAddress, headerValue);
    }

    public ClientIpResolution resolve(String remoteAddress, String headerValue) {
        boolean trustedProxy = isTrustedProxy(remoteAddress);
        if (!trustedProxy) {
            return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, false, "直连来源不在可信代理网段");
        }
        String headerAddress = firstValidAddress(headerValue);
        if (headerAddress == null) {
            return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, true, "可信代理未提供有效客户端 IP");
        }
        return new ClientIpResolution(headerAddress, remoteAddress, headerValue, true, "已使用可信代理客户端 IP 头");
    }

    public String resolveHeaderName() {
        String configuredHeader = configManager.getString(CONFIG_KEY, "client_ip_header", "X-Forwarded-For");
        if (configuredHeader == null || configuredHeader.isBlank()) {
            return "X-Forwarded-For";
        }
        return configuredHeader.trim();
    }

    private boolean isTrustedProxy(String remoteAddress) {
        InetAddress address = parseAddress(remoteAddress);
        if (address == null) {
            return false;
        }
        List<String> cidrs = new ArrayList<>();
        String configuredCidrs = configManager.getString(CONFIG_KEY, "trusted_proxy_cidrs", DEFAULT_TRUSTED_CIDRS);
        String[] parts = configuredCidrs.split(",");
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                cidrs.add(part.trim());
            }
        }
        for (String cidr : cidrs) {
            if (matchesCidr(address, cidr)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(InetAddress address, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            return false;
        }
        InetAddress networkAddress = parseAddress(parts[0]);
        if (networkAddress == null || networkAddress.getAddress().length != address.getAddress().length) {
            return false;
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(parts[1]);
        } catch (Exception exception) {
            return false;
        }
        int maxPrefixLength = address.getAddress().length * 8;
        if (prefixLength < 0 || prefixLength > maxPrefixLength) {
            return false;
        }
        byte[] addressBytes = address.getAddress();
        byte[] networkBytes = networkAddress.getAddress();
        int remainingBits = prefixLength;
        for (int index = 0; index < addressBytes.length; index = index + 1) {
            int comparedBits = Math.min(8, remainingBits);
            if (comparedBits == 0) {
                return true;
            }
            int mask = 255 << (8 - comparedBits);
            if ((addressBytes[index] & mask) != (networkBytes[index] & mask)) {
                return false;
            }
            remainingBits = remainingBits - comparedBits;
        }
        return true;
    }

    private String firstValidAddress(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String[] parts = headerValue.split(",");
        for (String part : parts) {
            String candidate = part.trim();
            if (parseAddress(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private InetAddress parseAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(value.trim());
        } catch (Exception exception) {
            return null;
        }
    }

    public record ClientIpResolution(String clientIp, String remoteIp, String headerValue, boolean trustedProxy, String message) {
    }
}