package com.biliwind.blog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ArticleModeConverter implements AttributeConverter<ArticleMode, Short> {

    @Override
    public Short convertToDatabaseColumn(ArticleMode attribute) {
        return attribute == null ? ArticleMode.STANDARD.code() : attribute.code();
    }

    @Override
    public ArticleMode convertToEntityAttribute(Short dbData) {
        return dbData == null ? ArticleMode.STANDARD : ArticleMode.fromCode(dbData);
    }
}
