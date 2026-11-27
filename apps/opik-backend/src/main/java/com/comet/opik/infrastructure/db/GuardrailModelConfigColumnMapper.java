package com.comet.opik.infrastructure.db;

import com.comet.opik.api.GuardrailModelConfig;
import com.comet.opik.utils.JsonUtils;

public class GuardrailModelConfigColumnMapper extends AbstractJsonColumnMapper<GuardrailModelConfig> {

    @Override
    protected GuardrailModelConfig deserialize(String json) {
        return JsonUtils.readValue(json, GuardrailModelConfig.class);
    }
}
