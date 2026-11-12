package com.biliwind.blog.service.ai;

/** The AI operations a caller may request through {@link AiCallExecutor}. */
public enum AiOperation {
    SUMMARIZE,
    TRANSLATE,
    MODERATE,
    COMPLETE,
    COMPLETE_STREAM,
    TEST_STREAM
}
