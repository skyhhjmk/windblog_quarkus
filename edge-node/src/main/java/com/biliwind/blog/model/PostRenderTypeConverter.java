package com.biliwind.blog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class PostRenderTypeConverter implements AttributeConverter<PostRenderType, Short> {

    @Override
    public Short convertToDatabaseColumn(PostRenderType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public PostRenderType convertToEntityAttribute(Short dbData) {
        return PostRenderType.fromCode(dbData);
    }
}
