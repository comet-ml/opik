package com.comet.opik.domain;

import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.utils.JsonUtils;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.UUID;

@UtilityClass
class ExecutionPolicyMapper {

    String serialize(ExecutionPolicy executionPolicy) {
        if (executionPolicy == null) {
            return "";
        }
        return JsonUtils.writeValueAsString(executionPolicy);
    }

    ExecutionPolicy fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtils.readValue(json, ExecutionPolicy.class);
    }

    ExperimentItem resolvePolicy(
            ExperimentItem item,
            Map<UUID, ExperimentDAO.ExperimentPolicyInfo> experimentInfoMap,
            Map<UUID, Map<UUID, ExecutionPolicy>> policiesByVersion) {

        if (item.executionPolicy() != null) {
            return item;
        }

        var info = experimentInfoMap.get(item.experimentId());
        ExecutionPolicy policy = null;

        if (info != null && info.datasetVersionId() != null) {
            var versionPolicies = policiesByVersion.get(info.datasetVersionId());
            if (versionPolicies != null) {
                policy = versionPolicies.get(item.datasetItemId());
            }
        }
        if (policy == null && info != null) {
            policy = info.policy();
        }
        if (policy == null) {
            policy = ExecutionPolicy.DEFAULT;
        }

        return item.toBuilder().executionPolicy(policy).build();
    }
}
