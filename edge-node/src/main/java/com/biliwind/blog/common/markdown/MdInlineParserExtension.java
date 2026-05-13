package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.InlineParserExtension;
import com.vladsch.flexmark.parser.InlineParserExtensionFactory;
import com.vladsch.flexmark.parser.LightInlineParser;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MdInlineParserExtension implements InlineParserExtension {
    private static final Pattern KEYBOARD_PATTERN = Pattern.compile("^\\[\\[(.+?)\\]\\]");
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("^\\[%\\s*(\\d+)\\s*%\\]");
    private static final Pattern STATUS_BADGE_PATTERN = Pattern.compile("^\\[!!\\s*(\\w+)\\s*\\|\\s*(.+?)\\s*\\]");

    @Override
    public void finalizeDocument(@NotNull InlineParser inlineParser) {
    }

    @Override
    public void finalizeBlock(@NotNull InlineParser inlineParser) {
    }

    @Override
    public boolean parse(@NotNull LightInlineParser inlineParser) {
        if (inlineParser.peek() == '[') {
            BasedSequence input = inlineParser.getInput();
            int index = inlineParser.getIndex();
            BasedSequence remainder = input.subSequence(index);

            // Keyboard
            Matcher kbdMatcher = KEYBOARD_PATTERN.matcher(remainder);
            if (kbdMatcher.find()) {
                inlineParser.setIndex(index + kbdMatcher.end());
                MdNodes.KeyboardNode node = new MdNodes.KeyboardNode(remainder.subSequence(0, kbdMatcher.end()));
                node.appendChild(new com.vladsch.flexmark.ast.Text(kbdMatcher.group(1)));
                inlineParser.getBlock().appendChild(node);
                return true;
            }

            // Progress
            Matcher progMatcher = PROGRESS_PATTERN.matcher(remainder);
            if (progMatcher.find()) {
                inlineParser.setIndex(index + progMatcher.end());
                int value = Integer.parseInt(progMatcher.group(1));
                MdNodes.ProgressNode node = new MdNodes.ProgressNode(remainder.subSequence(0, progMatcher.end()), value);
                inlineParser.getBlock().appendChild(node);
                return true;
            }

            // Status Badge
            Matcher statusMatcher = STATUS_BADGE_PATTERN.matcher(remainder);
            if (statusMatcher.find()) {
                inlineParser.setIndex(index + statusMatcher.end());
                String status = statusMatcher.group(1);
                String label = statusMatcher.group(2);
                MdNodes.StatusBadgeNode node = new MdNodes.StatusBadgeNode(remainder.subSequence(0, statusMatcher.end()), status, label);
                inlineParser.getBlock().appendChild(node);
                return true;
            }
        }
        return false;
    }

    public static class Factory implements InlineParserExtensionFactory {
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
        public CharSequence getCharacters() {
            return "[";
        }

        @NotNull
        @Override
        public InlineParserExtension apply(@NotNull LightInlineParser lightInlineParser) {
            return new MdInlineParserExtension();
        }
    }
}
