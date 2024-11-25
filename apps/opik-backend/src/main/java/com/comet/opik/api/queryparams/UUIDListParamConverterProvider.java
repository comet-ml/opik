package com.comet.opik.api.queryparams;

import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Type;
import java.util.List;

@Provider
public class UUIDListParamConverterProvider implements ParamConverterProvider {

    @Override
    @SuppressWarnings("unchecked")
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType,
            java.lang.annotation.Annotation[] annotations) {
        if (rawType.equals(List.class) && genericType.getTypeName().contains("UUID")) {
            return (ParamConverter<T>) new UUIDListParamConverter();
        }
        return null;
    }
}
