package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.Post;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Long getExtraPointsPrice(Post post) {
        if (post.extraInfo != null) {
            try {
                JsonNode extraNode = MAPPER.convertValue(post.extraInfo, JsonNode.class);
                if (extraNode.has("points_price")) {
                    return extraNode.get("points_price").asLong(0);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    public static int getFreeLines(Post post) {
        if (post.extraInfo != null) {
            try {
                JsonNode extraNode = MAPPER.convertValue(post.extraInfo, JsonNode.class);
                if (extraNode.has("free_lines")) {
                    return extraNode.get("free_lines").asInt(0);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return 0;
    }

    public static void updateExtraInfo(Post post, Long pointsPrice, Integer freeLines) {
        Map<String, Object> extra = null;
        if (post.extraInfo instanceof Map) {
            extra = new HashMap<>((Map<String, Object>) post.extraInfo);
        } else if (post.extraInfo != null) {
            try {
                extra = MAPPER.convertValue(post.extraInfo, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                extra = new HashMap<>();
            }
        } else {
            extra = new HashMap<>();
        }

        if (pointsPrice != null) {
            extra.put("points_price", pointsPrice);
        }
        if (freeLines != null) {
            extra.put("free_lines", freeLines);
        }

        post.extraInfo = extra;
    }

    /**
     * 为 Markdown 多语言内容中的隐藏短代码注入唯一标识 UUID
     */
    public static Map<String, String> injectBlockIds(Map<String, String> localizedContent) {
        if (localizedContent == null || localizedContent.isEmpty()) {
            return localizedContent;
        }

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : localizedContent.entrySet()) {
            result.put(entry.getKey(), injectBlockIdToContent(entry.getValue()));
        }
        return result;
    }

    private static String injectBlockIdToContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // 匹配 [hide-text ...] 或 [hide-attachment ...]
        Pattern p = Pattern.compile("\\[\\s*(hide-text|hide-attachment)(.*?)\\]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {
            String tagType = m.group(1);
            String attrs = m.group(2);

            // 如果该短代码还没有 id 属性，则为其注入一个基于 UUID 的标识
            if (!attrs.toLowerCase().contains("id=")) {
                String newId = UUID.randomUUID().toString();
                String replacement = "[" + tagType + " id=\"" + newId + "\"" + attrs + "]";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
