package com.biliwind.blog.model;

public enum AiProvider {
    OLLAMA("OLLAMA"),
    CHATGLM("CHATGLM"),
    OPENAI("OPENAI");

    private final String key;

    AiProvider(String key) {
        this.key = key;
    }

    public static AiProvider from(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AiProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String getKey() {
        return key;
    }
}
