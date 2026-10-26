package com.biliwind.blog.common.dto;

import io.quarkus.qute.TemplateData;

@TemplateData
public record SearchHighlightSegment(String text, boolean highlighted) {
}
