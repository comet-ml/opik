package com.comet.opik.infrastructure.db;

import com.comet.opik.api.GuardrailPolicyRule;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Comparator;
import java.util.List;

public class GuardrailPolicyRulesColumnMapper extends AbstractJsonColumnMapper<List<GuardrailPolicyRule>> {

    private static final TypeReference<List<GuardrailPolicyRule>> TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    protected List<GuardrailPolicyRule> deserialize(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        // MySQL's JSON_ARRAYAGG takes no ORDER BY, so the rules are put in a stable order here.
        return JsonUtils.readValue(json, TYPE_REFERENCE).stream()
                .sorted(Comparator.comparing(GuardrailPolicyRule::type))
                .toList();
    }
}
