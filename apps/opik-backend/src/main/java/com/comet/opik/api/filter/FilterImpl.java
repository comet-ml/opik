package com.comet.opik.api.filter;

import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
@Data
public abstract class FilterImpl implements Filter {

    private final @NonNull Field field;
    private final @NonNull Operator operator;
    private final String key;
    private final @NonNull String value;

    @Override
    public Filter buildFromCustom(String customField, FieldType type, Operator operator, String key, String value) {
        return null; // custom filters are not supported by default
    }
}
