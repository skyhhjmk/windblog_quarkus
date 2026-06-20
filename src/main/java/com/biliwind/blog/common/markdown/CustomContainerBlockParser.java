package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.parser.block.*;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomContainerBlockParser extends AbstractBlockParser {
    private static final Pattern START_PATTERN = Pattern.compile("^\\s*:::\\s+([a-zA-Z0-9_-]+)(?:\\s+\\{([^}]+)\\})?\\s*$");

    private final MdNodes.CustomContainerBlock block;
    private final String name;

    public CustomContainerBlockParser(BasedSequence chars, String name) {
        this(chars, name, java.util.Collections.emptyMap());
    }

    public CustomContainerBlockParser(BasedSequence chars, String name, java.util.Map<String, String> attrs) {
        this.name = name.trim();
        this.block = new MdNodes.CustomContainerBlock(chars, this.name);
        if (attrs != null) {
            this.block.setGroup(attrs.get("group"));
            this.block.setTitle(attrs.get("title"));
            String excludeStr = attrs.get("exclude");
            if (excludeStr != null && !excludeStr.isBlank()) {
                java.util.Set<String> regions = new java.util.HashSet<>();
                String[] parts = excludeStr.split(",");
                for (String part : parts) {
                    regions.add(part.trim().toLowerCase());
                }
                this.block.setExcludeRegions(regions);
            }
        }
    }

    private static java.util.Map<String, String> parseAttributes(String attrStr) {
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        if (attrStr == null || attrStr.isBlank()) {
            return attrs;
        }
        Pattern p = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s}]+))");
        Matcher m = p.matcher(attrStr);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            if (val != null) {
                attrs.put(key, val.trim());
            }
        }
        return attrs;
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        BasedSequence line = state.getLine();
        String trimmed = line.toString().trim();
        if (trimmed.equals("::: /" + this.name)) {
            this.block.setClosed(true);
            return BlockContinue.finished();
        }
        return BlockContinue.atIndex(state.getIndex());
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    @Override
    public boolean canContain(ParserState state, BlockParser blockParser, Block block) {
        return true;
    }

    @Override
    public void closeBlock(ParserState state) {
    }

    public static class Factory implements CustomBlockParserFactory {
        @Nullable
        @Override
        public Set<Class<?>> getAfterDependents() {
            return null;
        }

        @Nullable
        @Override
        public Set<Class<?>> getBeforeDependents() {
            return null;
        }

        @Override
        public boolean affectsGlobalScope() {
            return false;
        }

        @NotNull
        @Override
        public BlockParserFactory apply(@NotNull DataHolder options) {
            return new BlockParserFactory() {
                @Override
                public BlockStart tryStart(@NotNull ParserState state, @NotNull MatchedBlockParser matchedBlockParser) {
                    BasedSequence line = state.getLine();
                    Matcher matcher = START_PATTERN.matcher(line.toString().trim());
                    if (matcher.matches()) {
                        String name = matcher.group(1).trim();
                        String attrStr = matcher.group(2);
                        java.util.Map<String, String> attrs = parseAttributes(attrStr);
                        return BlockStart.of(new CustomContainerBlockParser(line, name, attrs)).atIndex(state.getLineEndIndex());
                    }
                    return BlockStart.none();
                }
            };
        }
    }
}
