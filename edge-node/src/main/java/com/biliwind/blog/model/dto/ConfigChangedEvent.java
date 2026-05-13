package com.biliwind.blog.model.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class ConfigChangedEvent {
    public final String key;
    public final JsonNode newValue;

    public ConfigChangedEvent(String key, JsonNode newValue) {
        this.key = key;
        this.newValue = newValue;
    }
}
