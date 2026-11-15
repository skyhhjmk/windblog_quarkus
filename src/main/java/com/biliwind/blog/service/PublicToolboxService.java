package com.biliwind.blog.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stateless, unauthenticated developer toolbox calculations shared by
 * public HTTP endpoints and the public MCP endpoint.
 */
@ApplicationScoped
public class PublicToolboxService {

    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    public Map<String, Object> calculateSubnet(String rawCidr) {
        if (rawCidr == null || rawCidr.isBlank()) {
            throw new IllegalArgumentException("cidr is required, e.g. 192.168.1.10/24");
        }
        String trimmed = rawCidr.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException("Invalid CIDR notation. Expected ip/prefix, e.g. 10.0.0.1/24");
        }
        String ipPart = trimmed.substring(0, slash).trim();
        int prefix;
        try {
            prefix = Integer.parseInt(trimmed.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR prefix length");
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipPart);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IP address: " + ipPart);
        }

        if (addr instanceof Inet4Address) {
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("IPv4 prefix must be between 0 and 32");
            }
            byte[] bytes = addr.getAddress();
            long ip = ((bytes[0] & 0xFFL) << 24)
                    | ((bytes[1] & 0xFFL) << 16)
                    | ((bytes[2] & 0xFFL) << 8)
                    | (bytes[3] & 0xFFL);
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            long wildcard = (~mask) & 0xFFFFFFFFL;
            long network = ip & mask;
            long broadcast = network | wildcard;
            long total = 1L << (32 - prefix);
            long usable = prefix >= 31 ? (prefix == 32 ? 1L : 2L) : Math.max(0L, total - 2L);
            long firstUsable = prefix >= 31 ? network : network + 1;
            long lastUsable = prefix >= 31 ? broadcast : broadcast - 1;

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("version", 4);
            res.put("input", trimmed);
            res.put("cidr", formatIpv4(network) + "/" + prefix);
            res.put("prefix", prefix);
            res.put("networkAddress", formatIpv4(network));
            res.put("broadcastAddress", formatIpv4(broadcast));
            res.put("netmask", formatIpv4(mask));
            res.put("wildcardMask", formatIpv4(wildcard));
            res.put("firstUsable", formatIpv4(firstUsable));
            res.put("lastUsable", formatIpv4(lastUsable));
            res.put("totalAddresses", total);
            res.put("usableHosts", usable);
            return res;
        }

        if (addr instanceof Inet6Address) {
            if (prefix < 0 || prefix > 128) {
                throw new IllegalArgumentException("IPv6 prefix must be between 0 and 128");
            }
            BigInteger ip = new BigInteger(1, addr.getAddress());
            BigInteger fullMask = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
            int hostBits = 128 - prefix;
            BigInteger hostMask = hostBits == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE);
            BigInteger netMask = fullMask.xor(hostMask);
            BigInteger network = ip.and(netMask);
            BigInteger last = network.or(hostMask);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("version", 6);
            res.put("input", trimmed);
            res.put("prefix", prefix);
            res.put("networkAddress", formatIpv6(network));
            res.put("lastAddress", formatIpv6(last));
            res.put("totalAddresses", BigInteger.ONE.shiftLeft(hostBits).toString());
            return res;
        }

        throw new IllegalArgumentException("Unsupported IP address type");
    }

    public Map<String, Object> convertTimestamp(String rawValue) {
        Instant instant;
        String input = rawValue == null ? "" : rawValue.trim();
        if (input.isEmpty() || "now".equalsIgnoreCase(input)) {
            instant = Instant.now();
        } else if (input.matches("^-?\\d+$")) {
            long num;
            try {
                num = Long.parseLong(input);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Timestamp out of range");
            }
            if (Math.abs(num) >= 100_000_000_000L) {
                instant = Instant.ofEpochMilli(num);
            } else {
                instant = Instant.ofEpochSecond(num);
            }
        } else {
            try {
                instant = Instant.parse(input);
            } catch (Exception e) {
                try {
                    instant = ZonedDateTime.parse(input).toInstant();
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Expected epoch seconds/millis, ISO-8601 timestamp, or 'now'");
                }
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("input", input.isEmpty() ? "now" : input);
        res.put("epochSeconds", instant.getEpochSecond());
        res.put("epochMillis", instant.toEpochMilli());
        res.put("isoUtc", DateTimeFormatter.ISO_INSTANT.format(instant));
        res.put("isoAsiaShanghai", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(SHANGHAI_OFFSET)));
        res.put("rfc1123", DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC)));
        return res;
    }

    public Map<String, Object> nextCronRuns(String expr, int count) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        int limitedCount = Math.max(1, Math.min(count, 10));
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5 && parts.length != 6) {
            throw new IllegalArgumentException("Cron expression must have 5 (Linux) or 6 (Quartz/Spring) fields");
        }

        boolean hasSeconds = parts.length == 6;
        int offset = hasSeconds ? 1 : 0;
        BitSet seconds = hasSeconds ? parseCronField(parts[0], 0, 59, false) : singleBit(0);
        BitSet minutes = parseCronField(parts[offset], 0, 59, false);
        BitSet hours = parseCronField(parts[offset + 1], 0, 23, false);
        BitSet dom = parseCronField(parts[offset + 2], 1, 31, false);
        BitSet months = parseCronField(parts[offset + 3], 1, 12, false);
        BitSet dow = parseCronField(parts[offset + 4], 0, 7, true);

        ZonedDateTime cursor = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        ZonedDateTime limit = cursor.plusYears(2);
        List<Map<String, Object>> runs = new ArrayList<>();

        int iterations = 0;
        while (runs.size() < limitedCount && cursor.isBefore(limit) && iterations < 500_000) {
            iterations++;
            if (!months.get(cursor.getMonthValue())) {
                cursor = cursor.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            int dayOfWeek = cursor.getDayOfWeek().getValue() % 7; // Sunday = 0
            if (!dom.get(cursor.getDayOfMonth()) || !dow.get(dayOfWeek)) {
                cursor = cursor.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!hours.get(cursor.getHour())) {
                cursor = cursor.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.get(cursor.getMinute())) {
                cursor = cursor.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds.get(cursor.getSecond())) {
                cursor = cursor.plusSeconds(1);
                continue;
            }
            Instant instant = cursor.toInstant();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("utc", DateTimeFormatter.ISO_INSTANT.format(instant));
            item.put("asiaShanghai", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(SHANGHAI_OFFSET)));
            item.put("epochSeconds", instant.getEpochSecond());
            runs.add(item);
            cursor = cursor.plusSeconds(hasSeconds ? 1 : 60);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("expression", expr.trim());
        res.put("format", hasSeconds ? "6-field (second minute hour day month weekday)" : "5-field (minute hour day month weekday)");
        res.put("nextRuns", runs);
        return res;
    }

    public Map<String, Object> computeHash(String text, String algorithm) {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text exceeds 256KB limit");
        }
        String normalizedAlg = (algorithm == null || algorithm.isBlank())
                ? "SHA-256"
                : algorithm.trim().toUpperCase(Locale.ROOT);
        String jcaAlg = switch (normalizedAlg) {
            case "MD5" -> "MD5";
            case "SHA1", "SHA-1" -> "SHA-1";
            case "SHA256", "SHA-256" -> "SHA-256";
            case "SHA512", "SHA-512" -> "SHA-512";
            default -> throw new IllegalArgumentException("Unsupported algorithm. Use MD5, SHA-1, SHA-256, or SHA-512");
        };

        try {
            MessageDigest md = MessageDigest.getInstance(jcaAlg);
            String hex = HexFormat.of().formatHex(md.digest(bytes));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("algorithm", jcaAlg);
            res.put("byteLength", bytes.length);
            res.put("hex", hex);
            return res;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm unavailable: " + jcaAlg, e);
        }
    }

    public Map<String, Object> estimateTokens(String text) {
        String safe = text == null ? "" : text;
        if (safe.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("text exceeds 256KB limit");
        }
        int cjkChars = 0;
        int asciiChars = 0;
        int otherChars = 0;
        for (int i = 0; i < safe.length(); i++) {
            char ch = safe.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjkChars++;
            } else if (ch < 128) {
                asciiChars++;
            } else {
                otherChars++;
            }
        }
        int words = safe.isBlank() ? 0 : safe.trim().split("\\s+").length;
        int lines = safe.isEmpty() ? 0 : safe.split("\\R", -1).length;
        int estimatedTokens = safe.isEmpty() ? 0
                : Math.max(1, (int) Math.ceil(cjkChars * 1.45 + asciiChars / 4.0 + otherChars * 1.1));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("characters", safe.length());
        res.put("cjkCharacters", cjkChars);
        res.put("words", words);
        res.put("lines", lines);
        res.put("estimatedTokens", estimatedTokens);
        res.put("estimationNote", "Heuristic estimate (~1.45 tokens/CJK char, ~0.25 tokens/ASCII char)");
        return res;
    }

    private static String formatIpv4(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private static String formatIpv6(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] sixteen = new byte[16];
        int copyLen = Math.min(raw.length, 16);
        System.arraycopy(raw, Math.max(0, raw.length - 16), sixteen, 16 - copyLen, copyLen);
        try {
            return InetAddress.getByAddress(sixteen).getHostAddress();
        } catch (Exception e) {
            return value.toString(16);
        }
    }

    private static BitSet singleBit(int index) {
        BitSet bits = new BitSet(60);
        bits.set(index);
        return bits;
    }

    private static BitSet parseCronField(String field, int min, int max, boolean normalizeWeekday) {
        BitSet bits = new BitSet(max + 1);
        if ("?".equals(field)) {
            bits.set(min, max + 1);
            return bits;
        }
        for (String token : field.split(",")) {
            String[] stepParts = token.split("/", 2);
            int step = 1;
            if (stepParts.length == 2) {
                step = Integer.parseInt(stepParts[1]);
                if (step <= 0) {
                    throw new IllegalArgumentException("Invalid cron step: " + token);
                }
            }
            String range = stepParts[0];
            int start;
            int end;
            if ("*".equals(range)) {
                start = min;
                end = max;
            } else if (range.contains("-")) {
                String[] bounds = range.split("-", 2);
                start = Integer.parseInt(bounds[0]);
                end = Integer.parseInt(bounds[1]);
            } else {
                start = Integer.parseInt(range);
                end = stepParts.length == 2 ? max : start;
            }
            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException("Cron field out of range: " + token);
            }
            for (int v = start; v <= end; v += step) {
                int normalized = (normalizeWeekday && v == 7) ? 0 : v;
                bits.set(normalized);
            }
        }
        return bits;
    }
}
