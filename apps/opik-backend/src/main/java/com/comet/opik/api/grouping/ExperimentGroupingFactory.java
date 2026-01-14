package com.comet.opik.api.grouping;

import java.util.List;

public class ExperimentGroupingFactory extends GroupingFactory {
    @Override
    public List<String> getSupportedFields() {
        return List.of(METADATA, DATASET_ID, PROJECT_ID);
    }
}
