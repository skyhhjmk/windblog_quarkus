package com.biliwind.blog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AiPollingAlgorithm {
    ROUND_ROBIN("ROUND_ROBIN"),
    WEIGHTED_ROUND_ROBIN("WEIGHTED_ROUND_ROBIN"),
    MULTI_MASTER_MULTI_BACKUP("MULTI_MASTER_MULTI_BACKUP");

    private final String value;

    AiPollingAlgorithm(String value) {
        this.value = value;
    }

    @JsonCreator
    public static AiPollingAlgorithm from(String v) {
        for (AiPollingAlgorithm alg : values()) {
            if (alg.value.equalsIgnoreCase(v)) {
                return alg;
            }
        }
        return ROUND_ROBIN;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
