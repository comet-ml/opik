package com.comet.opik.infrastructure.db;

import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.utils.JsonUtils;

public class ExecutionPolicyColumnMapper extends AbstractJsonColumnMapper<ExecutionPolicy> {

    @Override
    protected ExecutionPolicy deserialize(String json) {
        if (isBlank(json)) {
            return null;
        }
        return JsonUtils.readValue(json, ExecutionPolicy.class);
    }
}
