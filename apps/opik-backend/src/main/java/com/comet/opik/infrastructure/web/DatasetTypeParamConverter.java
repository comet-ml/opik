package com.comet.opik.infrastructure.web;

import com.comet.opik.api.DatasetType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DatasetTypeParamConverter extends AbstractParamConverterProvider<DatasetType> {

    public DatasetTypeParamConverter() {
        super(DatasetType.class);
    }

    @Override
    protected DatasetType parse(String value) {
        return DatasetType.fromString(value)
                .orElseThrow(() -> new BadRequestException(
                        "Invalid dataset type: '%s'. Expected one of: dataset, evaluation_suite"
                                .formatted(value)));
    }

    @Override
    protected String format(DatasetType value) {
        return value != null ? value.getValue() : null;
    }
}
