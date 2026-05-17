package com.biliwind.blog.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * BlogRegion JPA 转换器，确保存储 code 到数据库
 */
@Converter(autoApply = true)
public class BlogRegionConverter implements AttributeConverter<BlogRegion, String> {

    @Override
    public String convertToDatabaseColumn(BlogRegion attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCode();
    }

    @Override
    public BlogRegion convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return BlogRegion.fromCode(dbData);
    }
}
