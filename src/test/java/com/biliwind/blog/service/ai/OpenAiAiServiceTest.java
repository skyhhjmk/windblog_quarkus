package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiAiServiceTest {

    @Test
    void shouldCleanJsonTextWithMarkdownFences() {
        OpenAiAiService service = new OpenAiAiService();

        assertEquals("", service.cleanJsonText(null));
        assertEquals("", service.cleanJsonText("   "));

        String normalJson = "{\"title\":\"Hello\",\"content\":\"World\"}";
        assertEquals(normalJson, service.cleanJsonText(normalJson));

        String fencedJson = "```json\n" + normalJson + "\n```";
        assertEquals(normalJson, service.cleanJsonText(fencedJson));

        String fencedWithoutLang = "```\n" + normalJson + "\n```";
        assertEquals(normalJson, service.cleanJsonText(fencedWithoutLang));

        String fencedWithSpaces = "   ```JSON \n  " + normalJson + " \n ```  ";
        assertEquals(normalJson, service.cleanJsonText(fencedWithSpaces));
    }
}
