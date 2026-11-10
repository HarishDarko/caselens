package com.harishdarko.caselens.triage;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a category as its name in the existing varchar(32) columns, so stored values do not change. */
@Converter
public class CategoryConverter implements AttributeConverter<Category, String> {
    @Override
    public String convertToDatabaseColumn(Category category) {
        return category == null ? null : category.name();
    }

    @Override
    public Category convertToEntityAttribute(String name) {
        return name == null ? null : Category.of(name);
    }
}
