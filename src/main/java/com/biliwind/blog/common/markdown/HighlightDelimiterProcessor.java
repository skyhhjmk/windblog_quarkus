package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.parser.core.delimiter.Delimiter;
import com.vladsch.flexmark.parser.delimiter.DelimiterProcessor;
import com.vladsch.flexmark.parser.delimiter.DelimiterRun;
import com.vladsch.flexmark.util.ast.DelimitedNode;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;

public class HighlightDelimiterProcessor implements DelimiterProcessor {
    @Override
    public char getOpeningCharacter() {
        return '=';
    }

    @Override
    public char getClosingCharacter() {
        return '=';
    }

    @Override
    public int getMinLength() {
        return 2;
    }

    @Override
    public int getDelimiterUse(DelimiterRun opener, DelimiterRun closer) {
        if (opener.length() >= 2 && closer.length() >= 2) {
            return 2;
        }
        return 0;
    }

    @Override
    public void process(Delimiter opener, Delimiter closer, int delimitersUsed) {
        HighlightNode node = new HighlightNode(opener.getTailChars(delimitersUsed));
        opener.moveNodesBetweenDelimitersTo(node, closer);
    }

    @Override
    public boolean canBeOpener(String before, String after, boolean leftFlanking, boolean rightFlanking, boolean beforeIsPunctuation, boolean afterIsPunctuation, boolean beforeIsWhitespace, boolean afterIsWhitespace) {
        return leftFlanking;
    }

    @Override
    public boolean canBeCloser(String before, String after, boolean leftFlanking, boolean rightFlanking, boolean beforeIsPunctuation, boolean afterIsPunctuation, boolean beforeIsWhitespace, boolean afterIsWhitespace) {
        return rightFlanking;
    }

    @Override
    public Node unmatchedDelimiterNode(com.vladsch.flexmark.parser.InlineParser inlineParser, DelimiterRun delimiter) {
        return null;
    }

    @Override
    public boolean skipNonOpenerCloser() {
        return false;
    }
    
    public static class HighlightNode extends MdNodes.HighlightNode implements DelimitedNode {
        private BasedSequence openingMarker = BasedSequence.NULL;
        private BasedSequence closingMarker = BasedSequence.NULL;
        private BasedSequence text = BasedSequence.NULL;

        public HighlightNode(BasedSequence chars) {
            super(chars);
        }

        @Override
        public BasedSequence getOpeningMarker() { return openingMarker; }
        @Override
        public void setOpeningMarker(BasedSequence openingMarker) { this.openingMarker = openingMarker; }
        @Override
        public BasedSequence getClosingMarker() { return closingMarker; }
        @Override
        public void setClosingMarker(BasedSequence closingMarker) { this.closingMarker = closingMarker; }
        @Override
        public BasedSequence getText() { return text; }
        @Override
        public void setText(BasedSequence text) { this.text = text; }
    }
}
