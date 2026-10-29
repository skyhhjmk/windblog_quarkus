package com.biliwind.blog.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nBundleTest {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("i18n\\.(?:t|format)\\('([^']+)'");

    @Test
    void bundlesShareTheSameKeys() throws IOException {
        Set<String> zh = keys("messages_zh_CN.properties");
        Set<String> en = keys("messages_en_US.properties");
        assertEquals(zh, en, "zh-cn and en-US bundles must define the same keys");
        assertTrue(zh.contains("common.home"));
    }

    @Test
    void everyTemplateKeyExists() throws IOException {
        Set<String> bundle = keys("messages_zh_CN.properties");
        Set<String> missing = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            files.filter(path -> path.toString().endsWith(".html")).forEach(path -> {
                try {
                    Matcher matcher = KEY_PATTERN.matcher(Files.readString(path, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (!bundle.contains(key)) {
                            missing.add(key + " (" + path.getFileName() + ")");
                        }
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
        assertTrue(missing.isEmpty(), "Missing i18n keys: " + missing);
    }

    private Set<String> keys(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("i18n/" + resource)) {
            assertTrue(input != null, resource + " not found");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return new HashSet<>(properties.stringPropertyNames());
    }
}
