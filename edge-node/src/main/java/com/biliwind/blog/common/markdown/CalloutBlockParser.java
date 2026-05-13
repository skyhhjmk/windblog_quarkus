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

public class CalloutBlockParser extends AbstractBlockParser {
    private static final Pattern START_PATTERN = Pattern.compile("^\\s*>(!!|[!?i])\\s*(.*)$");

    private final MdNodes.CalloutBlock block;

    public CalloutBlockParser(BasedSequence chars, String type, BasedSequence content) {
        this.block = new MdNodes.CalloutBlock(chars, type, content);
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        // These are usually single-paragraph or single-line for simplicity
        // But we can allow it to continue if the next line also starts with the same prefix?
        // Let's keep it simple: it's a block that contains what follows on the same line.
        return BlockContinue.none();
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
                        String symbol = matcher.group(1);
                        BasedSequence content = line.subSequence(matcher.start(2), matcher.end(2));
                        String type;
                        switch (symbol) {
                            case "!":
                                type = "warning";
                                break;
                            case "!!":
                                type = "danger";
                                break;
                            case "?":
                                type = "question";
                                break;
                            case "i":
                                type = "info";
                                break;
                            default:
                                type = "info";
                        }
                        // Consume the entire line to prevent swallowing subsequent content
                        return BlockStart.of(new CalloutBlockParser(line, type, content)).atIndex(state.getLineEndIndex());
                    }
                    return BlockStart.none();
                }
            };
        }
    }
}
