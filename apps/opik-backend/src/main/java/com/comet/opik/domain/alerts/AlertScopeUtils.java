package com.comet.opik.domain.alerts;

import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.utils.JsonUtils;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY;

@UtilityClass
public class AlertScopeUtils {

    /**
     * Returns the project IDs that define the scope of an alert trigger.
     * <p>
     * When the alert has a {@code projectId} set (new-style column), that value is used exclusively.
     * Otherwise the method falls back to the legacy {@code SCOPE_PROJECT} trigger config.
     *
     * @param alertProjectId the value of the alert's {@code project_id} column, may be {@code null}
     * @param triggerConfigs the trigger configs to inspect for the legacy scope
     * @return project IDs restricting the scope, or an empty list when the alert is workspace-wide
     */
    public static Set<UUID> collectProjectIds(UUID alertProjectId, List<AlertTriggerConfig> triggerConfigs) {
        if (alertProjectId != null) {
            return Set.of(alertProjectId);
        }
        var projectIdsString = Optional.ofNullable(triggerConfigs)
                .stream()
                .flatMap(List::stream)
                .filter(c -> c.type() == AlertTriggerConfigType.SCOPE_PROJECT)
                .findFirst()
                .map(AlertTriggerConfig::configValue)
                .map(v -> v.get(PROJECT_IDS_CONFIG_KEY))
                .orElse(null);
        if (StringUtils.isNotBlank(projectIdsString)) {
            return JsonUtils.readCollectionValue(projectIdsString, Set.class, UUID.class);
        }
        return Set.of();
    }
}
