package com.biliwind.blog.service.security;

import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class ClientIpResolver {

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_NETWORK = "network";
    public static final String SOURCE_HEADER = "header";

    private static final String DEFAULT_TRUSTED_CIDRS = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
    private static final String DEFAULT_HEADER = "X-Forwarded-For";

    @Inject
    SettingsReader settings;

    public ClientIpResolution resolve(RoutingContext routingContext) {
        String remoteAddress = "unknown";
        String headerValue = null;
        if (routingContext != null && routingContext.request() != null) {
            if (routingContext.request().remoteAddress() != null) {
                remoteAddress = routingContext.request().remoteAddress().hostAddress();
            }
            // A client can send the same header several times; proxies append
            // to the last line, so every line must be considered in order.
            List<String> values = routingContext.request().headers().getAll(resolveHeaderName());
            if (values != null && !values.isEmpty()) {
                headerValue = String.join(",", values);
            }
        }
        return resolve(remoteAddress, headerValue);
    }

    public ClientIpResolution resolve(String remoteAddress, String headerValue) {
        boolean trustedProxy = isTrustedProxy(remoteAddress);
        String source = resolveSource();
        if (SOURCE_NETWORK.equals(source)) {
            return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, trustedProxy,
                    "系统设置要求仅使用网络连接地址");
        }
        if (SOURCE_HEADER.equals(source)) {
            String headerAddress = clientAddressFromHeader(headerValue);
            if (headerAddress == null) {
                return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, trustedProxy,
                        "系统设置要求使用请求头，但请求头没有有效客户端 IP");
            }
            return new ClientIpResolution(headerAddress, remoteAddress, headerValue, trustedProxy,
                    "系统设置要求使用请求头客户端 IP");
        }
        if (!trustedProxy) {
            return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, false, "直连来源不在可信代理网段");
        }
        String headerAddress = clientAddressFromHeader(headerValue);
        if (headerAddress == null) {
            return new ClientIpResolution(remoteAddress, remoteAddress, headerValue, true, "可信代理未提供有效客户端 IP");
        }
        return new ClientIpResolution(headerAddress, remoteAddress, headerValue, true, "已使用可信代理客户端 IP 头");
    }

    public String resolveSource() {
        String configuredSource = settings.text(SettingKey.SECURITY_NETWORK, "client_ip_source");
        if (configuredSource == null || configuredSource.isBlank()) {
            return SOURCE_AUTO;
        }
        String normalized = configuredSource.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_NETWORK.equals(normalized) || SOURCE_HEADER.equals(normalized)) {
            return normalized;
        }
        return SOURCE_AUTO;
    }

    /**
     * The header that carries the visitor address. Any RFC 7230 token is
     * accepted, so CDN-specific single-value headers such as EO-Connecting-IP,
     * CF-Connecting-IP or X-Real-IP work as well as X-Forwarded-For/Forwarded.
     */
    public String resolveHeaderName() {
        String configuredHeader = settings.text(SettingKey.SECURITY_NETWORK, "client_ip_header");
        if (configuredHeader == null || configuredHeader.isBlank()) {
            return DEFAULT_HEADER;
        }
        String trimmed = configuredHeader.trim();
        return isHeaderToken(trimmed) ? trimmed : DEFAULT_HEADER;
    }

    static boolean isHeaderToken(String name) {
        if (name == null || name.isEmpty() || name.length() > 100) {
            return false;
        }
        for (int index = 0; index < name.length(); index = index + 1) {
            char current = name.charAt(index);
            boolean allowed = current >= 'a' && current <= 'z' || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9' || "!#$%&'*+-.^_`|~".indexOf(current) >= 0;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private boolean isTrustedProxy(String remoteAddress) {
        InetAddress address = parseAddress(remoteAddress);
        if (address == null) {
            return false;
        }
        return matchesAny(address, trustedCidrs());
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

    /**
     * Picks the visitor address from a forwarding header.
     *
     * Proxies append the address they saw to the right, and anything to the
     * left of that can be written by the client. The value is therefore read
     * from right to left, skipping hops inside the trusted proxy CIDRs; the
     * first address outside them is the client. When every hop is trusted
     * (an all-internal chain), the leftmost address is used. An unparsable
     * entry stops the walk, because nothing to its left can be attributed to
     * a trusted proxy. Single-value headers (X-Real-IP, EO-Connecting-IP, …)
     * yield that one address.
     */
    String clientAddressFromHeader(String headerValue) {
        List<String> candidates = headerCandidates(headerValue);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> cidrs = trustedCidrs();
        String leftmostTrusted = null;
        for (int index = candidates.size() - 1; index >= 0; index = index - 1) {
            String candidate = normalizeCandidate(candidates.get(index));
            InetAddress address = parseAddress(candidate);
            if (address == null) {
                return leftmostTrusted;
            }
            if (!matchesAny(address, cidrs)) {
                return candidate;
            }
            leftmostTrusted = candidate;
        }
        return leftmostTrusted;
    }

    private static List<String> headerCandidates(String headerValue) {
        List<String> candidates = new ArrayList<>();
        if (headerValue == null || headerValue.isBlank()) {
            return candidates;
        }
        for (String element : headerValue.split(",")) {
            String trimmed = element.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // RFC 7239 Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43
            String forwardedFor = null;
            for (String pair : trimmed.split(";")) {
                String[] keyValue = pair.trim().split("=", 2);
                if (keyValue.length == 2 && "for".equalsIgnoreCase(keyValue[0].trim())) {
                    forwardedFor = keyValue[1].trim();
                }
            }
            if (forwardedFor != null) {
                candidates.add(forwardedFor);
            } else if (trimmed.indexOf('=') < 0) {
                candidates.add(trimmed);
            }
        }
        return candidates;
    }

    /** Strips quotes, IPv6 brackets and a trailing port ("203.0.113.5:4711", "[2001:db8::1]:443"). */
    static String normalizeCandidate(String value) {
        String candidate = value.trim();
        if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            return close > 0 ? candidate.substring(1, close) : candidate;
        }
        int colon = candidate.indexOf(':');
        if (colon > 0 && colon == candidate.lastIndexOf(':') && candidate.indexOf('.') > 0) {
            return candidate.substring(0, colon);
        }
        return candidate;
    }

    private List<String> trustedCidrs() {
        List<String> cidrs = new ArrayList<>();
        String configuredCidrs = settings.text(SettingKey.SECURITY_NETWORK, "trusted_proxy_cidrs");
        if (configuredCidrs == null) {
            return cidrs;
        }
        for (String part : configuredCidrs.split("[,\\s]+")) {
            if (!part.isBlank()) {
                cidrs.add(part.trim());
            }
        }
        return cidrs;
    }

    private boolean matchesAny(InetAddress address, List<String> cidrs) {
        for (String cidr : cidrs) {
            if (matchesCidr(address, cidr)) {
                return true;
            }
        }
        return false;
    }

    private InetAddress parseAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!isAddressLiteral(normalized)) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (Exception exception) {
            return null;
        }
    }

    static boolean isAddressLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.indexOf(':') >= 0) {
            for (int index = 0; index < normalized.length(); index = index + 1) {
                char current = normalized.charAt(index);
                boolean hex = current >= '0' && current <= '9'
                        || current >= 'a' && current <= 'f'
                        || current >= 'A' && current <= 'F';
                if (!hex && current != ':' && current != '.') {
                    return false;
                }
            }
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int valuePart = 0;
            for (int index = 0; index < octet.length(); index = index + 1) {
                char current = octet.charAt(index);
                if (current < '0' || current > '9') {
                    return false;
                }
                valuePart = valuePart * 10 + current - '0';
            }
            if (valuePart > 255) {
                return false;
            }
        }
        return true;
    }

    @RegisterForReflection
    public record ClientIpResolution(String clientIp, String remoteIp, String headerValue, boolean trustedProxy, String message) {
    }
}
