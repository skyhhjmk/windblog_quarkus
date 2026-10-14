package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FakeAiServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private FakeAiService service() {
        FakeAiService service = new FakeAiService();
        service.mapper = mapper;
        return service;
    }

    @Test
    void listsProviderSpecificModelsAndRejectsInvalidRequests() throws Exception {
        FakeAiService service = service();
        var openai = mapper.valueToTree(service.models("openai").getEntity());
        var claude = mapper.valueToTree(service.models("claude").getEntity());
        assertEquals("list", openai.path("object").asText());
        assertEquals("GPT-100", openai.path("data").get(0).path("id").asText());
        assertEquals("Claude Opus 100", claude.path("data").get(0).path("display_name").asText());
        assertFalse(claude.path("has_more").asBoolean());
        assertEquals(404, service.models("unknown").getStatus());
        assertEquals(400, service.generate("openai", "chat/completions", mapper.readTree("{}")).getStatus());
        assertEquals(404, service.generate("claude", "chat/completions", mapper.readTree("{}")).getStatus());
        assertEquals(400, service.generate("claude", "messages", mapper.readTree(
                "{\"model\":\"Claude Opus 100\",\"messages\":[{}],\"max_tokens\":0}")).getStatus());
        assertTrue(FakeAiService.ART.startsWith("```text\n"));
        assertEquals(9, FakeAiService.ART.lines().count());
    }

    @Test
    void openaiStreamWaitsFiveSecondsAndTerminatesCorrectly() throws Exception {
        checkStream("openai", "chat/completions", "GPT-100");
    }

    @Test
    void claudeStreamWaitsFiveSecondsAndUsesNamedEvents() throws Exception {
        checkStream("claude", "messages", "Claude Opus 100");
    }

    private void checkStream(String provider, String endpoint, String model) throws Exception {
        FakeAiService service = service();
        var request = mapper.createObjectNode().put("model", model).put("stream", true).put("max_tokens", 1024);
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        var response = service.generate(provider, endpoint, request);
        assertEquals("text/event-stream", response.getMediaType().toString());
        long started = System.nanoTime();
        long[] firstFlush = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                if (firstFlush[0] == 0) firstFlush[0] = System.nanoTime();
            }
        };
        ((StreamingOutput) response.getEntity()).write(out);
        assertTrue((firstFlush[0] - started) / 1_000_000 < 1000);
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 5000);
        String stream = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        if (provider.equals("openai")) {
            assertTrue(stream.contains("chat.completion.chunk"));
            assertTrue(stream.endsWith("data: [DONE]\n\n"));
        } else {
            assertTrue(stream.contains("event: content_block_delta"));
            assertTrue(stream.contains("event: message_stop"));
        }
        assertTrue(stream.contains("```text"));
        for (String line : stream.lines().filter(line -> line.startsWith("data: {")).toList()) {
            assertTrue(mapper.readTree(line.substring(6)).isObject());
        }
    }
}
