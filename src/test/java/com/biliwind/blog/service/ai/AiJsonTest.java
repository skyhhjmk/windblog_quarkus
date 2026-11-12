package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiJsonTest {

    @Test
    void shouldCleanJsonTextWithMarkdownFences() {
        assertEquals("", AiJson.clean(null));
        assertEquals("", AiJson.clean("   "));

        String normalJson = "{\"title\":\"Hello\",\"content\":\"World\"}";
        assertEquals(normalJson, AiJson.clean(normalJson));

        String fencedJson = "```json\n" + normalJson + "\n```";
        assertEquals(normalJson, AiJson.clean(fencedJson));

        String fencedWithoutLang = "```\n" + normalJson + "\n```";
        assertEquals(normalJson, AiJson.clean(fencedWithoutLang));

        String fencedWithSpaces = "   ```JSON \n  " + normalJson + " \n ```  ";
        assertEquals(normalJson, AiJson.clean(fencedWithSpaces));
    }
}
