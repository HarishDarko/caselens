package com.harishdarko.caselens.triage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A triage category. The industry pack of a workspace decides which names exist; this type only checks the
 * shape of the name (upper case, digits and underscores, at most 32 characters to fit the database columns).
 */
public record Category(String name) {
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public Category {
        Objects.requireNonNull(name, "Category is required");
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Category name is not valid");
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Category of(String name) {
        return new Category(name);
    }

    @JsonValue
    @Override
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
