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
    private static final Pattern START_PATTERN = Pattern.compile("^\\s*:::\\s+([a-zA-Z0-9_-]+)\\s*$");

    private final MdNodes.CustomContainerBlock block;
    private final String name;

    public CustomContainerBlockParser(BasedSequence chars, String name) {
        this.name = name.trim();
        this.block = new MdNodes.CustomContainerBlock(chars, this.name);
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
                    Matcher matcher = START_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String name = matcher.group(1).trim();
                        return BlockStart.of(new CustomContainerBlockParser(line, name)).atIndex(state.getLineEndIndex());
                    }
                    return BlockStart.none();
                }
            };
        }
    }
}
