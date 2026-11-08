package com.biliwind.blog.model.translation;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Paragraph-level translation metadata for a specific target language in a revision.
 */
@RegisterForReflection
public record ParagraphTranslationMeta(
        String sourceLanguage,
        boolean outdated,
        int outdatedParagraphs,
        int totalParagraphs,
        String titleHash,
        String summaryHash,
        String seoTitleHash,
        String seoDescriptionHash,
        String seoKeywordsHash,
        List<ParagraphBlockItem> paragraphs
) {
    public ParagraphTranslationMeta {
        if (paragraphs == null) {
            paragraphs = List.of();
        }
    }
}
