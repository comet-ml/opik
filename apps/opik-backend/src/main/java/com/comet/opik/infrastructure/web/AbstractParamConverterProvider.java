package com.comet.opik.infrastructure.web;

import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Objects;

@RequiredArgsConstructor
public abstract class AbstractParamConverterProvider<T> implements ParamConverterProvider {

    private final @NonNull Class<T> targetType;

    @Override
    @SuppressWarnings("unchecked")
    public <U> ParamConverter<U> getConverter(Class<U> rawType, Type genericType, Annotation[] annotations) {
        if (rawType != targetType) {
            return null;
        }

        return (ParamConverter<U>) new ParamConverter<T>() {
            @Override
            public T fromString(String value) {
                if (StringUtils.isBlank(value)) {
                    return null;
                }
                return parse(value);
            }

            @Override
            public String toString(T value) {
                return format(value);
            }
        };
    }

    protected abstract T parse(String value);

    protected String format(T value) {
        return Objects.toString(value, null);
    }
}
