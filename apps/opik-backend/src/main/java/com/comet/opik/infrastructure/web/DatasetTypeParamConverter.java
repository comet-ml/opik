package com.comet.opik.infrastructure.web;

import com.comet.opik.api.DatasetType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.Provider;

import java.util.Arrays;
import java.util.stream.Collectors;

@Provider
public class DatasetTypeParamConverter extends AbstractParamConverterProvider<DatasetType> {

    private static final String ALLOWED_VALUES = Arrays.stream(DatasetType.values())
            .map(DatasetType::getValue)
            .collect(Collectors.joining(", "));

    public DatasetTypeParamConverter() {
        super(DatasetType.class);
    }

    @Override
    protected DatasetType parse(String value) {
        return DatasetType.fromString(value)
                .orElseThrow(() -> new BadRequestException(
                        "Invalid dataset type: '%s'. Expected one of: %s"
                                .formatted(value, ALLOWED_VALUES)));
    }

    @Override
    protected String format(DatasetType value) {
        return value != null ? value.getValue() : null;
    }
}
