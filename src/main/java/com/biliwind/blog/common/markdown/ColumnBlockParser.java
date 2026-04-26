package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.parser.block.*;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

public class ColumnBlockParser extends AbstractBlockParser {
    private static final Pattern START_PATTERN = Pattern.compile("^\\s*:::\\s*column\\s*$");
    private static final Pattern END_PATTERN = Pattern.compile("^\\s*:::\\s*$");

    private final MdNodes.ColumnBlock block;

    public ColumnBlockParser(BasedSequence chars) {
        this.block = new MdNodes.ColumnBlock(chars);
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        BasedSequence line = state.getLine();
        if (END_PATTERN.matcher(line).matches()) {
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
                    if (START_PATTERN.matcher(line).matches()) {
                        return BlockStart.of(new ColumnBlockParser(line)).atIndex(state.getIndex());
                    }
                    return BlockStart.none();
                }
            };
        }
    }
}
