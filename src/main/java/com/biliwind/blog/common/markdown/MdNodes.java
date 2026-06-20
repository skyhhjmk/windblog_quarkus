package com.biliwind.blog.common.markdown;

import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;

public class MdNodes {

    public static class CustomContainerBlock extends Block {
        private final String name;
        private boolean isClosed = false;
        private String group;
        private java.util.Set<String> excludeRegions = new java.util.HashSet<>();
        private String title;

        public CustomContainerBlock(BasedSequence chars, String name) {
            super(chars);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public boolean isClosed() {
            return isClosed;
        }

        public void setClosed(boolean closed) {
            this.isClosed = closed;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public java.util.Set<String> getExcludeRegions() {
            return excludeRegions;
        }

        public void setExcludeRegions(java.util.Set<String> excludeRegions) {
            this.excludeRegions = excludeRegions;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public boolean isExcluded(com.biliwind.blog.model.BlogRegion region) {
            if (region == null || excludeRegions == null) {
                return false;
            }
            return excludeRegions.contains(region.getCode().toLowerCase());
        }

        @Override
        public @NotNull BasedSequence[] getSegments() { return BasedSequence.EMPTY_SEGMENTS; }
    }

    public static class CalloutBlock extends Block {
        private final String type;
        private final BasedSequence content;
        public CalloutBlock(BasedSequence chars, String type, BasedSequence content) { 
            super(chars); 
            this.type = type; 
            this.content = content;
        }
        public String getType() { return type; }
        public BasedSequence getContent() { return content; }
        @Override
        public @NotNull BasedSequence[] getSegments() { return new BasedSequence[] { content }; }
    }

    public static class HighlightNode extends Node {
        public HighlightNode(BasedSequence chars) { super(chars); }
        @Override
        public @NotNull BasedSequence[] getSegments() { return BasedSequence.EMPTY_SEGMENTS; }
    }

    public static class KeyboardNode extends Node {
        public KeyboardNode(BasedSequence chars) { super(chars); }
        @Override
        public @NotNull BasedSequence[] getSegments() { return BasedSequence.EMPTY_SEGMENTS; }
    }

    public static class ProgressNode extends Node {
        private final int value;
        public ProgressNode(BasedSequence chars, int value) { super(chars); this.value = value; }
        public int getValue() { return value; }
        @Override
        public @NotNull BasedSequence[] getSegments() { return BasedSequence.EMPTY_SEGMENTS; }
    }

    public static class StatusBadgeNode extends Node {
        private final String status;
        private final String label;
        public StatusBadgeNode(BasedSequence chars, String status, String label) { super(chars); this.status = status; this.label = label; }
        public String getStatus() { return status; }
        public String getLabel() { return label; }
        @Override
        public @NotNull BasedSequence[] getSegments() { return BasedSequence.EMPTY_SEGMENTS; }
    }
}
