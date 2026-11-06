package com.biliwind.blog.service.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Shared validation for server-side HTTP endpoints before a connection is opened.
 */
public final class ExternalHttpEndpointPolicy {

    private ExternalHttpEndpointPolicy() {
    }

    public static URI validateHttpUri(URI uri, String label) {
        String valueLabel = label == null || label.isBlank() ? "外部地址" : label;
        if (uri == null) {
            throw new IllegalArgumentException(valueLabel + "不能为空");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(valueLabel + "只允许 HTTP 或 HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(valueLabel + "不允许携带用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(valueLabel + "缺少主机名");
        }
        return uri;
    }

    public static InetAddress[] resolveAddresses(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("外部地址缺少主机名");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("外部地址无法解析");
            }
            return addresses;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("外部地址无法解析");
        }
    }

    public static void requirePublicAddresses(String host) {
        InetAddress[] addresses = resolveAddresses(host);
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IllegalArgumentException("不允许访问非公网地址");
            }
        }
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            int firstByte = bytes[0] & 255;
            if ((firstByte & 254) == 252 || firstByte == 255
                    || (firstByte == 32 && (bytes[1] & 255) == 1
                    && (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184)) {
                return false;
            }
            byte[] embeddedIpv4 = embeddedIpv4Address(bytes);
            if (embeddedIpv4 != null) {
                return !isSpecialIpv4Address(embeddedIpv4);
            }
        }
        if (address instanceof Inet4Address) {
            return !isSpecialIpv4Address(address.getAddress());
        }
        return true;
    }

    private static boolean isSpecialIpv4Address(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            return true;
        }
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        int third = bytes[2] & 255;
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && (second == 0 || second == 168)) {
            return true;
        }
        if (first == 192 && second == 2) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;
        }
        if (first == 198 && second == 51 && third == 100) {
            return true;
        }
        return first == 203 && second == 0 && third == 113;
    }

    private static boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes == null || bytes.length != 16 || (bytes[10] & 255) != 255
                || (bytes[11] & 255) != 255) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the IPv4 address carried inside an IPv6 transition address, or
     * null when the address does not embed one. NAT64 (64:ff9b::/96 and the
     * local-use 64:ff9b:1::/48) and 6to4 (2002::/16) otherwise hide a private
     * IPv4 target behind an address that looks like a global IPv6 literal.
     */
    private static byte[] embeddedIpv4Address(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            return null;
        }
        if (isIpv4MappedAddress(bytes) || isIpv4CompatibleAddress(bytes)) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        if (isNat64WellKnownPrefix(bytes)) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        if (isNat64LocalUsePrefix(bytes)) {
            return new byte[]{bytes[6], bytes[7], bytes[8], bytes[9]};
        }
        if ((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x02) {
            return new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]};
        }
        return null;
    }

    private static boolean isIpv4CompatibleAddress(byte[] bytes) {
        for (int index = 0; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNat64WellKnownPrefix(byte[] bytes) {
        if ((bytes[0] & 255) != 0 || (bytes[1] & 255) != 0x64
                || (bytes[2] & 255) != 0xff || (bytes[3] & 255) != 0x9b) {
            return false;
        }
        for (int index = 4; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNat64LocalUsePrefix(byte[] bytes) {
        return (bytes[0] & 255) == 0 && (bytes[1] & 255) == 0x64
                && (bytes[2] & 255) == 0xff && (bytes[3] & 255) == 0x9b
                && (bytes[4] & 255) == 0 && (bytes[5] & 255) == 1;
    }
}
