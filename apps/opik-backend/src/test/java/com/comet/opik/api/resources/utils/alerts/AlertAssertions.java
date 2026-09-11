package com.comet.opik.api.resources.utils.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;

import java.util.Comparator;
import java.util.Optional;

import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;
import static com.comet.opik.infrastructure.EncryptionUtils.maskApiKey;
import static org.assertj.core.api.Assertions.assertThat;

public class AlertAssertions {

    public static final String[] ALERT_IGNORED_FIELDS = {
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "workspaceId", "webhook.name", "webhook.secretToken", "webhook.createdAt",
            "webhook.lastUpdatedAt",
            "webhook.createdBy", "webhook.lastUpdatedBy", "triggers"};

    public static final String[] TRIGGER_IGNORED_FIELDS = {
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "triggerConfigs"};

    public static final String[] TRIGGER_CONFIG_IGNORED_FIELDS = {
            "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};

    public static void assertAlerts(Alert expected, Alert actual, boolean decryptSecretToken) {
        var preparedExpected = prepareForComparison(expected, true);
        var preparedActual = prepareForComparison(actual, false);

        assertThat(preparedActual)
                .usingRecursiveComparison()
                .ignoringFields(ALERT_IGNORED_FIELDS)
                .ignoringCollectionOrder()
                .isEqualTo(preparedExpected);

        assertThat(preparedActual.triggers())
                .usingRecursiveComparison()
                .ignoringFields(TRIGGER_IGNORED_FIELDS)
                .ignoringCollectionOrder()
                .isEqualTo(preparedExpected.triggers());

        if (decryptSecretToken) {
            assertThat(decrypt(actual.webhook().secretToken()))
                    .isEqualTo(maskApiKey(expected.webhook().secretToken()));
        } else {
            assertThat(actual.webhook().secretToken()).isEqualTo(expected.webhook().secretToken());
        }

        for (int i = 0; i < preparedActual.triggers().size(); i++) {
            var actualTrigger = preparedActual.triggers().get(i);
            var expectedTrigger = preparedExpected.triggers().get(i);

            assertThat(actualTrigger.triggerConfigs())
                    .usingRecursiveComparison()
                    .ignoringFields(TRIGGER_CONFIG_IGNORED_FIELDS)
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedTrigger.triggerConfigs());
        }
    }

    public static Alert prepareForComparison(Alert alert, boolean isExpected) {
        var sortedTriggers = alert.triggers().stream()
                .map(trigger -> {
                    var configs = Optional.ofNullable(trigger.triggerConfigs())
                            .map(list -> list.stream()
                                    .map(config -> config.toBuilder()
                                            .alertTriggerId(isExpected ? trigger.id() : config.alertTriggerId())
                                            .build())
                                    .toList())
                            .orElse(null);
                    return trigger.toBuilder()
                            .triggerConfigs(configs)
                            .alertId(isExpected ? alert.id() : trigger.alertId())
                            .build();
                })
                .sorted(Comparator.comparing(AlertTrigger::id))
                .toList();

        return alert.toBuilder()
                .triggers(sortedTriggers)
                .build();
    }
}
