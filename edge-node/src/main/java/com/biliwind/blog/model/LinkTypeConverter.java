package com.biliwind.blog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class LinkTypeConverter implements AttributeConverter<LinkType, Short> {

    @Override
    public Short convertToDatabaseColumn(LinkType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public LinkType convertToEntityAttribute(Short dbData) {
        return LinkType.fromCode(dbData);
    }
}
