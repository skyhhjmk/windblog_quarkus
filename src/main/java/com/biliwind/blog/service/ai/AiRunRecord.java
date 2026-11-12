package com.biliwind.blog.service.ai;

import java.time.OffsetDateTime;

/** The fields a durable AI run exposes to the shared lifecycle. */
public interface AiRunRecord {

    String runStatus();

    OffsetDateTime runCreatedAt();

    OffsetDateTime runStartedAt();

    void markRunFailed(String message, OffsetDateTime now);

    void persistRun();
}
