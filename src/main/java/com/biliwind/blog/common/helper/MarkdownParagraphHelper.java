package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.markdown.MdProtocolExtension;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.translation.ParagraphBlockItem;
import com.biliwind.blog.model.translation.ParagraphTranslationMeta;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Paragraph-level Markdown splitting, hashing, and synchronization helper.
 */
public final class MarkdownParagraphHelper {

    public record SourceBlock(int index, String rawText, String hash) {}

    public record SyncResult(
            Map<String, String> contentMarkdown,
            Map<String, ParagraphTranslationMeta> paragraphTranslations
    ) {}

    public record IncrementalPrepResult(
            Map<String, String> fieldsToTranslate,
            List<SourceBlock> sourceBlocks,
            List<ParagraphBlockItem> currentParagraphs,
            boolean isFullRetranslation,
            String sourceTitle,
            String sourceSummary,
            String sourceSeoTitle,
            String sourceSeoDescription,
            String sourceSeoKeywords
    ) {}

    private static final class ParserHolder {
        private static volatile Parser parser;
    }

    private static Parser getParser() {
        Parser parser = ParserHolder.parser;
        if (parser != null) {
            return parser;
        }
        synchronized (MarkdownParagraphHelper.class) {
            if (ParserHolder.parser != null) {
                return ParserHolder.parser;
            }
            MutableDataSet flexmarkOptions = new MutableDataSet();
            flexmarkOptions.set(Parser.EXTENSIONS, List.of(
                    TablesExtension.create(),
                    MdProtocolExtension.create()));
            Parser created = Parser.builder(flexmarkOptions).build();
            ParserHolder.parser = created;
            return created;
        }
    }

    private MarkdownParagraphHelper() {}

    /**
     * Splits Markdown into top-level syntax blocks (paragraphs, headers, codeblocks, tables, blockquotes, etc.).
     */
    public static List<SourceBlock> splitBlocks(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        Document doc = getParser().parse(markdown);
        List<SourceBlock> blocks = new ArrayList<>();
        int index = 0;
        for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
            String blockText = child.getChars().toString().trim();
            if (!blockText.isEmpty()) {
                blocks.add(new SourceBlock(index++, blockText, hash(blockText)));
            }
        }
        return blocks;
    }

    /**
     * Calculates SHA-256 16-character hex hash of given text.
     */
    public static String hash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Assembles a list of paragraph items into a complete Markdown document.
     */
    public static String assembleMarkdown(List<ParagraphBlockItem> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(paragraphs.get(i).targetText());
        }
        return sb.toString();
    }

    /**
     * Synchronizes existing translations when the source content/title/summary is updated.
     * Retains unchanged paragraph translations and falls back modified paragraphs to source text,
     * marking them as outdated.
     */
    public static SyncResult syncOnSourceUpdate(
            Post post,
            PostRevision previousRevision,
            String sourceLanguage,
            String newSourceContent,
            Map<String, String> newTitle,
            Map<String, String> newSummary,
            Map<String, String> newSeoTitle,
            Map<String, String> newSeoDescription,
            Map<String, String> newSeoKeywords,
            Map<String, String> nextContentMarkdown
    ) {
        Map<String, String> updatedContent = new LinkedHashMap<>(nextContentMarkdown != null ? nextContentMarkdown : Map.of());
        Map<String, ParagraphTranslationMeta> updatedMeta = new LinkedHashMap<>();

        if (previousRevision != null && previousRevision.paragraphTranslations != null) {
            updatedMeta.putAll(previousRevision.paragraphTranslations);
        }

        List<SourceBlock> newSourceBlocks = splitBlocks(newSourceContent);
        String sourceTitle = LanguageHelper.resolveLocalizedValue(newTitle, sourceLanguage);
        String sourceSummary = LanguageHelper.resolveLocalizedValue(newSummary, sourceLanguage);
        String sourceSeoTitle = LanguageHelper.lookupIgnoreCase(newSeoTitle, sourceLanguage);
        String sourceSeoDesc = LanguageHelper.lookupIgnoreCase(newSeoDescription, sourceLanguage);
        String sourceSeoKeywords = LanguageHelper.lookupIgnoreCase(newSeoKeywords, sourceLanguage);

        String currentTitleHash = hash(sourceTitle);
        String currentSummaryHash = hash(sourceSummary);
        String currentSeoTitleHash = hash(sourceSeoTitle);
        String currentSeoDescHash = hash(sourceSeoDesc);
        String currentSeoKeywordsHash = hash(sourceSeoKeywords);

        // Find all other languages present in previous or next content
        Set<String> languages = new LinkedHashSet<>(updatedContent.keySet());
        if (previousRevision != null && previousRevision.contentMarkdown != null) {
            languages.addAll(previousRevision.contentMarkdown.keySet());
        }

        for (String lang : languages) {
            if (lang.equalsIgnoreCase(sourceLanguage)) {
                continue;
            }

            ParagraphTranslationMeta prevMeta = updatedMeta.get(lang);
            // Fallback lookup case-insensitively
            if (prevMeta == null) {
                for (Map.Entry<String, ParagraphTranslationMeta> entry : updatedMeta.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(lang)) {
                        prevMeta = entry.getValue();
                        break;
                    }
                }
            }

            // Map sourceHash -> translatedText from prior translation
            Map<String, String> hashToTranslation = new LinkedHashMap<>();
            if (prevMeta != null && prevMeta.paragraphs() != null) {
                for (ParagraphBlockItem item : prevMeta.paragraphs()) {
                    if (!item.outdated() && item.sourceHash() != null && !item.sourceHash().isBlank()) {
                        hashToTranslation.put(item.sourceHash(), item.targetText());
                    }
                }
            } else if (previousRevision != null && previousRevision.contentMarkdown != null) {
                // Lazy Bootstrap: attempt 1:1 alignment with existing whole translation if blocks match
                String prevTargetText = LanguageHelper.resolveLocalizedValue(previousRevision.contentMarkdown, lang);
                String prevSourceText = LanguageHelper.resolveLocalizedValue(previousRevision.contentMarkdown, sourceLanguage);
                if (prevTargetText != null && !prevTargetText.isBlank() && prevSourceText != null && !prevSourceText.isBlank()) {
                    List<SourceBlock> oldSourceBlocks = splitBlocks(prevSourceText);
                    List<SourceBlock> oldTargetBlocks = splitBlocks(prevTargetText);
                    if (oldSourceBlocks.size() == oldTargetBlocks.size()) {
                        for (int i = 0; i < oldSourceBlocks.size(); i++) {
                            hashToTranslation.put(oldSourceBlocks.get(i).hash(), oldTargetBlocks.get(i).rawText());
                        }
                    }
                }
            }

            List<ParagraphBlockItem> nextItems = new ArrayList<>();
            int outdatedCount = 0;

            for (int i = 0; i < newSourceBlocks.size(); i++) {
                SourceBlock sb = newSourceBlocks.get(i);
                String translated = hashToTranslation.get(sb.hash());
                if (translated != null && !translated.isBlank()) {
                    nextItems.add(new ParagraphBlockItem(i, sb.hash(), translated, false));
                } else {
                    // Outdated paragraph falls back to original source text
                    nextItems.add(new ParagraphBlockItem(i, sb.hash(), sb.rawText(), true));
                    outdatedCount++;
                }
            }

            boolean titleChanged = prevMeta != null && prevMeta.titleHash() != null
                    && !prevMeta.titleHash().isBlank() && !prevMeta.titleHash().equals(currentTitleHash);
            boolean summaryChanged = prevMeta != null && prevMeta.summaryHash() != null
                    && !prevMeta.summaryHash().isBlank() && !prevMeta.summaryHash().equals(currentSummaryHash);
            boolean seoTitleChanged = prevMeta != null && prevMeta.seoTitleHash() != null
                    && !prevMeta.seoTitleHash().isBlank() && !prevMeta.seoTitleHash().equals(currentSeoTitleHash);
            boolean seoDescChanged = prevMeta != null && prevMeta.seoDescriptionHash() != null
                    && !prevMeta.seoDescriptionHash().isBlank() && !prevMeta.seoDescriptionHash().equals(currentSeoDescHash);
            boolean seoKeywordsChanged = prevMeta != null && prevMeta.seoKeywordsHash() != null
                    && !prevMeta.seoKeywordsHash().isBlank() && !prevMeta.seoKeywordsHash().equals(currentSeoKeywordsHash);

            boolean isOutdated = outdatedCount > 0 || titleChanged || summaryChanged
                    || seoTitleChanged || seoDescChanged || seoKeywordsChanged;

            ParagraphTranslationMeta newMeta = new ParagraphTranslationMeta(
                    sourceLanguage,
                    isOutdated,
                    outdatedCount,
                    newSourceBlocks.size(),
                    prevMeta != null ? prevMeta.titleHash() : currentTitleHash,
                    prevMeta != null ? prevMeta.summaryHash() : currentSummaryHash,
                    prevMeta != null ? prevMeta.seoTitleHash() : currentSeoTitleHash,
                    prevMeta != null ? prevMeta.seoDescriptionHash() : currentSeoDescHash,
                    prevMeta != null ? prevMeta.seoKeywordsHash() : currentSeoKeywordsHash,
                    nextItems
            );

            updatedMeta.put(lang, newMeta);
            updatedContent.put(lang, assembleMarkdown(nextItems));
        }

        return new SyncResult(updatedContent, updatedMeta);
    }

    /**
     * Prepares incremental or full translation request fields for AI.
     */
    public static IncrementalPrepResult prepareTranslationFields(
            String sourceLanguage,
            String targetLanguage,
            String sourceTitle,
            String sourceSummary,
            String sourceContent,
            String sourceSeoTitle,
            String sourceSeoDescription,
            String sourceSeoKeywords,
            ParagraphTranslationMeta existingMeta,
            boolean overwrite
    ) {
        List<SourceBlock> sourceBlocks = splitBlocks(sourceContent);
        List<ParagraphBlockItem> currentParagraphs = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();

        boolean isFull = overwrite || existingMeta == null || existingMeta.paragraphs() == null
                || existingMeta.paragraphs().isEmpty() || existingMeta.paragraphs().size() != sourceBlocks.size();

        if (isFull) {
            fields.put("title", sourceTitle != null ? sourceTitle : "");
            if (sourceSummary != null && !sourceSummary.isBlank()) {
                fields.put("summary", sourceSummary);
            }
            if (sourceSeoTitle != null && !sourceSeoTitle.isBlank()) {
                fields.put("seoTitle", sourceSeoTitle);
            }
            if (sourceSeoDescription != null && !sourceSeoDescription.isBlank()) {
                fields.put("seoDescription", sourceSeoDescription);
            }
            if (sourceSeoKeywords != null && !sourceSeoKeywords.isBlank()) {
                fields.put("seoKeywords", sourceSeoKeywords);
            }

            for (int i = 0; i < sourceBlocks.size(); i++) {
                SourceBlock sb = sourceBlocks.get(i);
                fields.put("p_" + i, sb.rawText());
                currentParagraphs.add(new ParagraphBlockItem(i, sb.hash(), sb.rawText(), true));
            }
        } else {
            // Incremental: only translate fields whose hash changed or paragraphs marked outdated
            String titleHash = hash(sourceTitle);
            String summaryHash = hash(sourceSummary);
            String seoTitleHash = hash(sourceSeoTitle);
            String seoDescHash = hash(sourceSeoDescription);
            String seoKeywordsHash = hash(sourceSeoKeywords);

            if (existingMeta.titleHash() == null || !existingMeta.titleHash().equals(titleHash)) {
                fields.put("title", sourceTitle != null ? sourceTitle : "");
            }
            if (sourceSummary != null && !sourceSummary.isBlank() &&
                    (existingMeta.summaryHash() == null || !existingMeta.summaryHash().equals(summaryHash))) {
                fields.put("summary", sourceSummary);
            }
            if (sourceSeoTitle != null && !sourceSeoTitle.isBlank() &&
                    (existingMeta.seoTitleHash() == null || !existingMeta.seoTitleHash().equals(seoTitleHash))) {
                fields.put("seoTitle", sourceSeoTitle);
            }
            if (sourceSeoDescription != null && !sourceSeoDescription.isBlank() &&
                    (existingMeta.seoDescriptionHash() == null || !existingMeta.seoDescriptionHash().equals(seoDescHash))) {
                fields.put("seoDescription", sourceSeoDescription);
            }
            if (sourceSeoKeywords != null && !sourceSeoKeywords.isBlank() &&
                    (existingMeta.seoKeywordsHash() == null || !existingMeta.seoKeywordsHash().equals(seoKeywordsHash))) {
                fields.put("seoKeywords", sourceSeoKeywords);
            }

            for (int i = 0; i < sourceBlocks.size(); i++) {
                SourceBlock sb = sourceBlocks.get(i);
                ParagraphBlockItem prevItem = i < existingMeta.paragraphs().size() ? existingMeta.paragraphs().get(i) : null;
                if (prevItem != null && !prevItem.outdated() && sb.hash().equals(prevItem.sourceHash())) {
                    currentParagraphs.add(prevItem);
                } else {
                    fields.put("p_" + i, sb.rawText());
                    currentParagraphs.add(new ParagraphBlockItem(i, sb.hash(), sb.rawText(), true));
                }
            }

            // In case no paragraphs were outdated but user clicked retranslate, include all paragraphs
            if (fields.isEmpty()) {
                fields.put("title", sourceTitle != null ? sourceTitle : "");
                for (int i = 0; i < sourceBlocks.size(); i++) {
                    fields.put("p_" + i, sourceBlocks.get(i).rawText());
                }
            }
        }

        return new IncrementalPrepResult(
                fields,
                sourceBlocks,
                currentParagraphs,
                isFull,
                sourceTitle,
                sourceSummary,
                sourceSeoTitle,
                sourceSeoDescription,
                sourceSeoKeywords
        );
    }

    /**
     * Applies AI translation result into the paragraph list and returns updated metadata and markdown.
     */
    public static ParagraphTranslationMeta applyTranslationResult(
            String sourceLanguage,
            String sourceTitle,
            String sourceSummary,
            String sourceSeoTitle,
            String sourceSeoDescription,
            String sourceSeoKeywords,
            List<SourceBlock> sourceBlocks,
            List<ParagraphBlockItem> currentParagraphs,
            Map<String, String> aiTranslatedFields
    ) {
        List<ParagraphBlockItem> resultParagraphs = new ArrayList<>();
        int outdatedCount = 0;

        for (int i = 0; i < sourceBlocks.size(); i++) {
            SourceBlock sb = sourceBlocks.get(i);
            String translated = aiTranslatedFields.get("p_" + i);
            if (translated != null && !translated.isBlank()) {
                resultParagraphs.add(new ParagraphBlockItem(i, sb.hash(), translated.trim(), false));
            } else {
                ParagraphBlockItem existing = i < currentParagraphs.size() ? currentParagraphs.get(i) : null;
                if (existing != null && !existing.outdated() && sb.hash().equals(existing.sourceHash())) {
                    resultParagraphs.add(existing);
                } else {
                    resultParagraphs.add(new ParagraphBlockItem(i, sb.hash(), sb.rawText(), true));
                    outdatedCount++;
                }
            }
        }

        return new ParagraphTranslationMeta(
                sourceLanguage,
                outdatedCount > 0,
                outdatedCount,
                sourceBlocks.size(),
                hash(sourceTitle),
                hash(sourceSummary),
                hash(sourceSeoTitle),
                hash(sourceSeoDescription),
                hash(sourceSeoKeywords),
                resultParagraphs
        );
    }

    /**
     * Renders HTML with subtle visual highlights and badges for outdated/untranslated paragraphs.
     */
    public static String renderHtmlWithOutdatedMarkers(
            String contentMarkdown,
            ParagraphTranslationMeta meta,
            String fallbackBadgeLabel
    ) {
        if (contentMarkdown == null || contentMarkdown.isBlank()) {
            return "";
        }
        if (meta == null || !meta.outdated() || meta.paragraphs() == null || meta.paragraphs().isEmpty()) {
            return MarkdownHelper.toHtml(contentMarkdown);
        }

        String badgeText = fallbackBadgeLabel != null && !fallbackBadgeLabel.isBlank()
                ? fallbackBadgeLabel
                : "原文（未同步）";

        StringBuilder sb = new StringBuilder();
        for (ParagraphBlockItem item : meta.paragraphs()) {
            String blockHtml = MarkdownHelper.toHtml(item.targetText());
            if (blockHtml.isBlank()) {
                continue;
            }
            if (item.outdated()) {
                sb.append("<div class=\"translation-outdated-block group relative my-4 rounded-r-lg border-l-4 border-amber-500/80 bg-amber-500/5 py-2 pl-4 pr-3 transition-colors hover:bg-amber-500/10\" data-translation-status=\"outdated\">\n")
                  .append("  <div class=\"mb-1 flex items-center gap-1.5 text-xs font-semibold text-amber-600 dark:text-amber-400 select-none\">\n")
                  .append("    <span class=\"inline-block h-1.5 w-1.5 rounded-full bg-amber-500\"></span>\n")
                  .append("    <span>").append(badgeText).append("</span>\n")
                  .append("  </div>\n")
                  .append("  ").append(blockHtml).append("\n")
                  .append("</div>\n");
            } else {
                sb.append(blockHtml).append("\n");
            }
        }
        return sb.toString();
    }
}
