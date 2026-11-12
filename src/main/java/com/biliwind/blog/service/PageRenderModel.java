package com.biliwind.blog.service;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.EntityTag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The inputs of one rendered public page. The same object feeds the template
 * and produces the validator, so a conditional request can never compare an
 * ETag that was built from a different input set than the HTML. Adding an
 * input means calling {@link #put(String, Object)}; it is hashed automatically.
 * Adding data after {@link #validator()} has sealed the model fails fast.
 */
public final class PageRenderModel {

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, Supplier<?>> derivedData = new LinkedHashMap<>();
    private boolean sealed;

    public PageRenderModel put(String key, Object value) {
        if (sealed) {
            throw new IllegalStateException(
                    "PageRenderModel is sealed; add every render input before computing the validator");
        }
        data.put(key, value);
        return this;
    }

    /**
     * Marks a template value that is computed only at render time. Derived
     * values are functions of the hashed inputs (rendered HTML, signed tokens)
     * and must not contribute to the validator.
     */
    public PageRenderModel derived(String key, Supplier<?> value) {
        if (sealed) {
            throw new IllegalStateException(
                    "PageRenderModel is sealed; add every render input before computing the validator");
        }
        derivedData.put(key, value);
        return this;
    }

    public EntityTag validator() {
        sealed = true;
        return new EntityTag(fingerprint(), true);
    }

    public TemplateInstance render(Template template) {
        sealed = true;
        TemplateInstance instance = template.instance();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            instance.data(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Supplier<?>> entry : derivedData.entrySet()) {
            instance.data(entry.getKey(), entry.getValue().get());
        }
        return instance;
    }

    public Map<String, Object> data() {
        return Collections.unmodifiableMap(data);
    }

    public Set<String> derivedKeys() {
        return Collections.unmodifiableSet(derivedData.keySet());
    }

    public String fingerprint() {
        List<String> entries = new ArrayList<>(data.size());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            StringBuilder text = new StringBuilder(entry.getKey()).append('=');
            appendValue(text, entry.getValue());
            entries.add(text.toString());
        }
        Collections.sort(entries);
        return sha256(String.join("\n", entries));
    }

    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                StringBuilder text = new StringBuilder();
                appendValue(text, entry.getKey());
                text.append('=');
                appendValue(text, entry.getValue());
                entries.add(text.toString());
            }
            Collections.sort(entries);
            out.append('{').append(String.join(",", entries)).append('}');
            return;
        }
        if (value instanceof Set<?> set) {
            List<String> entries = new ArrayList<>(set.size());
            for (Object entry : set) {
                StringBuilder text = new StringBuilder();
                appendValue(text, entry);
                entries.add(text.toString());
            }
            Collections.sort(entries);
            out.append('[').append(String.join(",", entries)).append(']');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object entry : iterable) {
                if (!first) {
                    out.append(',');
                }
                appendValue(out, entry);
                first = false;
            }
            out.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    out.append(',');
                }
                appendValue(out, java.lang.reflect.Array.get(value, index));
            }
            out.append(']');
            return;
        }
        out.append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(Character.forDigit((current >> 4) & 0xF, 16));
                hex.append(Character.forDigit(current & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for page validators", exception);
        }
    }
}
