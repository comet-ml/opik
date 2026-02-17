package com.comet.opik.infrastructure.db;

import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

public class EvaluatorItemListColumnMapper extends AbstractJsonColumnMapper<List<EvaluatorItem>> {

    private static final TypeReference<List<EvaluatorItem>> TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    protected List<EvaluatorItem> deserialize(String json) {
        if (isBlank(json) || EvaluatorItem.EMPTY_LIST_JSON.equals(json)) {
            return null;
        }
        return JsonUtils.readValue(json, TYPE_REFERENCE);
    }
}
