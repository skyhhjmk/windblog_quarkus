package com.biliwind.blog.service.ai;

/** Parsing helpers shared by every provider's structured (JSON) replies. */
public final class AiJson {

    private AiJson() {
    }

    public static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return text;
    }
}
