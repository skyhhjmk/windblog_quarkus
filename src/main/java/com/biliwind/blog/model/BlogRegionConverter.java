package com.biliwind.blog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * BlogRegion 枚举与数据库字符串的自动转换器
 */
@Converter(autoApply = true)
public class BlogRegionConverter implements AttributeConverter<BlogRegion, String> {

    @Override
    public String convertToDatabaseColumn(BlogRegion attribute) {
        if (attribute == null) {
            return BlogRegion.GLOBAL.getCode();
        }
        return attribute.getCode();
    }

    @Override
    public BlogRegion convertToEntityAttribute(String dbData) {
        return BlogRegion.fromCode(dbData);
    }
}
