package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;

@ApplicationScoped
public class AiTaskProducer {

    @Inject
    @Channel("ai-summary-tasks")
    Emitter<AiSummaryTask> taskEmitter;

    public void sendSummaryTask(AiSummaryTask task) {
        // Set RabbitMQ priority if possible (RabbitMQ supports 0-255, usually 0-10)
        // Convert our 0(High)-2(Low) to RabbitMQ priorities (e.g., 10, 5, 0)
        int rabbitPriority = switch (task.priority()) {
            case 0 -> 10;
            case 1 -> 5;
            default -> 0;
        };

        OutgoingRabbitMQMetadata metadata = OutgoingRabbitMQMetadata.builder()
                .withPriority(rabbitPriority)
                .build();

        taskEmitter.send(org.eclipse.microprofile.reactive.messaging.Message.of(task, Metadata.of(metadata)));
    }
}
