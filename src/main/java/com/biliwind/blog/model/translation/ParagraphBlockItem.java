package com.biliwind.blog.model.translation;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Single paragraph translation entry.
 *
 * @param index 0-based paragraph index in source article
 * @param sourceHash SHA-256 hash (truncated) of source paragraph content
 * @param targetText Translated markdown text (or fallback source text if outdated)
 * @param outdated Whether this paragraph is outdated/untranslated
 */
@RegisterForReflection
public record ParagraphBlockItem(
        int index,
        String sourceHash,
        String targetText,
        boolean outdated
) {
}
