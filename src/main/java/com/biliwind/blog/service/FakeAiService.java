package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A deterministic simulator: never sends prompts to a model provider. */
@ApplicationScoped
public class FakeAiService {
    public static final String OPENAI_MODEL = "GPT-100";
    public static final String CLAUDE_MODEL = "Claude Opus 100";
    public static final long THINKING_MILLIS = 5000;
    public static final String ART = buildArt();
    @Inject
    ObjectMapper mapper;

    public Response models(String provider) {
        if ("openai".equals(provider)) {
            return response(Map.of("object", "list", "data", List.of(Map.of(
                    "id", OPENAI_MODEL, "object", "model", "created", 0, "owned_by", "GeekCoreLab"))));
        }
        if ("claude".equals(provider)) {
            return response(Map.of("data", List.of(Map.of("id", CLAUDE_MODEL, "type", "model",
                    "display_name", CLAUDE_MODEL, "created_at", "2026-01-01T00:00:00Z")),
                    "has_more", false, "first_id", CLAUDE_MODEL, "last_id", CLAUDE_MODEL));
        }
        return error(provider, 404, "Unknown fake AI provider");
    }

    public Response generate(String provider, String endpoint, JsonNode request) {
        boolean claude = "claude".equals(provider);
        if (!(claude && "messages".equals(endpoint))
                && !("openai".equals(provider) && "chat/completions".equals(endpoint))) {
            return error(provider, 404, "Unknown fake AI endpoint");
        }
        String model = claude ? CLAUDE_MODEL : OPENAI_MODEL;
        if (request == null || !request.isObject() || !request.path("model").isTextual()
                || !model.equals(request.path("model").asText())
                || !request.path("messages").isArray() || request.path("messages").isEmpty()
                || (request.has("stream") && !request.path("stream").isBoolean())
                || (claude && (!request.path("max_tokens").isIntegralNumber()
                    || request.path("max_tokens").asLong() <= 0))) {
            return error(provider, 400, "Provide the listed model and a non-empty messages array"
                    + (claude ? "; max_tokens must be a positive integer" : ""));
        }
        String id = (claude ? "msg_" : "chatcmpl-") + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        if (request.path("stream").asBoolean(false)) {
            StreamingOutput stream = out -> {
                if (claude) {
                    Map<String, Object> start = new LinkedHashMap<>(message(id, model, List.of()));
                    start.put("stop_reason", null);
                    event(out, "message_start", Map.of("type", "message_start", "message", start));
                    event(out, "ping", Map.of("type", "ping"));
                } else {
                    event(out, null, chunk(id, model, created, Map.of("role", "assistant", "content", "", "reasoning_content", "Thinking..."), false));
                    out.write(": Thinking...\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                pause();
                if (claude) {
                    event(out, "content_block_start", Map.of("type", "content_block_start", "index", 0,
                            "content_block", Map.of("type", "text", "text", "")));
                    event(out, "content_block_delta", Map.of("type", "content_block_delta", "index", 0,
                            "delta", Map.of("type", "text_delta", "text", ART)));
                    event(out, "content_block_stop", Map.of("type", "content_block_stop", "index", 0));
                    event(out, "message_delta", Map.of("type", "message_delta", "delta",
                            stopDelta(),
                            "usage", Map.of("output_tokens", 0)));
                    event(out, "message_stop", Map.of("type", "message_stop"));
                } else {
                    event(out, null, chunk(id, model, created, Map.of("content", ART), false));
                    event(out, null, chunk(id, model, created, Map.of(), true));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            };
            return Response.ok(stream, "text/event-stream").header("Cache-Control", "no-store")
                    .header("X-Accel-Buffering", "no").build();
        }
        try {
            pause();
        } catch (IOException e) {
            Thread.currentThread().interrupt();
            return error(provider, 503, "Fake generation interrupted");
        }
        if (claude) return response(message(id, model, List.of(Map.of("type", "text", "text", ART))));
        return response(Map.of("id", id, "object", "chat.completion", "created", created, "model", model,
                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", ART),
                        "finish_reason", "stop")), "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0)));
    }

    private Map<String, Object> message(String id, String model, List<?> content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", content);
        message.put("stop_reason", "end_turn");
        message.put("stop_sequence", null);
        message.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
        return message;
    }

    private Map<String, Object> stopDelta() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", "end_turn");
        delta.put("stop_sequence", null);
        return delta;
    }

    private Map<String, Object> chunk(String id, String model, long created, Map<?, ?> delta, boolean stop) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", stop ? "stop" : null);
        return Map.of("id", id, "object", "chat.completion.chunk", "created", created, "model", model,
                "choices", List.of(choice));
    }

    private void event(OutputStream out, String name, Object payload) throws IOException {
        String data = (name == null ? "" : "event: " + name + "\n")
                + "data: " + mapper.writeValueAsString(payload) + "\n\n";
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pause() throws IOException {
        try {
            Thread.sleep(THINKING_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while thinking", e);
        }
    }

    private Response response(Object entity) {
        return Response.ok(entity).header("Cache-Control", "no-store").build();
    }

    private Response error(String provider, int status, String message) {
        Object entity = "claude".equals(provider)
                ? Map.of("type", "error", "error", Map.of("type", "invalid_request_error", "message", message))
                : Map.of("error", Map.of("type", "invalid_request_error", "message", message));
        return Response.status(status).entity(entity).header("Cache-Control", "no-store").build();
    }

    private static String buildArt() {
        // Five by seven glyphs spell GeekCoreLab; every lit pixel uses that character's own letter.
        String[] glyphs = {
            "01110/10000/10000/10111/10001/10001/01110", // G
            "00000/00000/01110/10001/11111/10000/01110", // e
            "00000/00000/01110/10001/11111/10000/01110", // e
            "10000/10000/10010/10100/11000/10100/10010", // k
            "01111/10000/10000/10000/10000/10000/01111", // C
            "00000/00000/01110/10001/10001/10001/01110", // o
            "00000/00000/10110/11001/10000/10000/10000", // r
            "00000/00000/01110/10001/11111/10000/01110", // e
            "10000/10000/10000/10000/10000/10000/11111", // L
            "00000/00000/01110/00001/01111/10001/01111", // a
            "10000/10000/10110/11001/10001/10001/11110"  // b
        };
        String label = "GeekCoreLab";
        StringBuilder result = new StringBuilder("```text\n");
        for (int row = 0; row < 7; row++) {
            for (int letter = 0; letter < label.length(); letter++) {
                String pixels = glyphs[letter].split("/")[row];
                for (int col = 0; col < 5; col++) {
                    result.append(pixels.charAt(col) == '1' ? label.charAt(letter) : ' ');
                }
                if (letter + 1 < label.length()) result.append("  ");
            }
            result.append('\n');
        }
        return result.append("```\n").toString();
    }
}
