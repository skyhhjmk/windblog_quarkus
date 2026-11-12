package com.biliwind.blog.service.ai;

import java.time.Duration;

/** Data describing one kind of durable AI run; the lifecycle code is shared. */
public enum AiRunKind {
    TOPIC_DISCOVERY(Duration.ofMinutes(10), Duration.ofMinutes(30)),
    ARTICLE_DRAFT(Duration.ofMinutes(10), Duration.ofMinutes(30));

    private final Duration queuedTimeout;
    private final Duration runningTimeout;

    AiRunKind(Duration queuedTimeout, Duration runningTimeout) {
        this.queuedTimeout = queuedTimeout;
        this.runningTimeout = runningTimeout;
    }

    public Duration queuedTimeout() {
        return queuedTimeout;
    }

    public Duration runningTimeout() {
        return runningTimeout;
    }

    public String queuedFailureMessage() {
        return "任务未被消费（服务重启或调度失败），请重新发起";
    }

    public String runningFailureMessage() {
        return "任务超时，请重新发起";
    }
}
