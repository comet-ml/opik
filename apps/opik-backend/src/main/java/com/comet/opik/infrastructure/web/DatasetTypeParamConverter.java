package com.comet.opik.infrastructure.web;

import com.comet.opik.api.DatasetType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@Provider
public class DatasetTypeParamConverter implements ParamConverterProvider {

    private static final DatasetTypeConverter INSTANCE = new DatasetTypeConverter();

    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (rawType != DatasetType.class) {
            return null;
        }

        return (ParamConverter<T>) INSTANCE;
    }

    private static class DatasetTypeConverter implements ParamConverter<DatasetType> {

        @Override
        public DatasetType fromString(String value) {
            if (StringUtils.isEmpty(value)) {
                return null;
            }

            return DatasetType.fromString(value)
                    .orElseThrow(() -> new BadRequestException(
                            "Invalid dataset type: '%s'. Expected one of: dataset, evaluation_suite"
                                    .formatted(value)));
        }

        @Override
        public String toString(DatasetType value) {
            return value != null ? value.getValue() : null;
        }
    }
}
