package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DatasetType;

public class DatasetTypeMapper extends AbstractEnumColumnMapper<DatasetType> {
    public DatasetTypeMapper() {
        super(DatasetType::fromValue, "dataset_type");
    }
}
