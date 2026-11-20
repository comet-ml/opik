package com.comet.opik.infrastructure.db;

import com.comet.opik.api.view.ViewInput;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

public class ViewInputsColumnMapper extends AbstractJsonColumnMapper<Map<String, ViewInput>> {

    private static final TypeReference<Map<String, ViewInput>> TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    protected Map<String, ViewInput> deserialize(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        return JsonUtils.readValue(json, TYPE_REFERENCE);
    }
}
