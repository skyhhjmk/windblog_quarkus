package com.biliwind.blog.common.dto;

import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;

@TemplateData
@RegisterForReflection
public record SearchHighlightSegment(String text, boolean highlighted) {
}
