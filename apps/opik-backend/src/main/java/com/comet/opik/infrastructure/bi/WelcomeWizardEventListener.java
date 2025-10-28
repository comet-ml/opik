package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.events.WelcomeWizardSubmitted;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WelcomeWizardEventListener {

    private static final String BI_EVENT_TYPE = "opik_os_welcome_wizard_submitted";

    private final @NonNull BiEventService biEventService;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull OpikConfiguration config;

    @Subscribe
    public void onWelcomeWizardSubmitted(WelcomeWizardSubmitted event) {
        if (!config.getUsageReport().isEnabled()) {
            return;
        }

        sendBiEvent(event);
    }

    private void sendBiEvent(WelcomeWizardSubmitted event) {
        try {
            // Check if this is an empty submission (dismissed without providing data)
            boolean isEmpty = event.email() == null
                    && event.role() == null
                    && (event.integrations() == null || event.integrations().isEmpty())
                    && event.joinBetaProgram() == null;

            if (isEmpty) {
                log.info("Skipping BI event for empty welcome wizard submission (dismissed) for workspace: '{}'",
                        event.workspaceId());
                return;
            }

            var anonymousId = usageReportService.getAnonymousId().orElse(null);
            if (anonymousId == null) {
                log.warn("Anonymous ID not available, skipping BI event for welcome wizard");
                return;
            }

            Map<String, String> eventProperties = new HashMap<>();
            eventProperties.put("opik_app_version", config.getMetadata().getVersion());
            eventProperties.put("date", Instant.now().toString());

            if (event.email() != null) {
                eventProperties.put("email", event.email());
            }
            if (event.role() != null) {
                eventProperties.put("role", event.role());
            }
            if (event.integrations() != null && !event.integrations().isEmpty()) {
                eventProperties.put("integrations", String.join(",", event.integrations()));
                eventProperties.put("integrations_count", String.valueOf(event.integrations().size()));
            }
            if (event.joinBetaProgram() != null) {
                eventProperties.put("join_beta_program", String.valueOf(event.joinBetaProgram()));
            }

            // Use workspace-specific event type for tracking (consistent with DAO)
            String eventType = "welcome_wizard_" + event.workspaceId();
            biEventService.reportEvent(anonymousId, eventType, BI_EVENT_TYPE, eventProperties);
            log.info("BI event sent for welcome wizard submission for workspace: '{}'",
                    event.workspaceId());
        } catch (Exception e) {
            log.error("Failed to send BI event for welcome wizard for workspace: '{}'", event.workspaceId(),
                    e);
        }
    }
}
