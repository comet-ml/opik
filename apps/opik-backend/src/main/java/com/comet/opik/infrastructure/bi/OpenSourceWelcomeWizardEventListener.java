package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.events.OpenSourceWelcomeWizardSubmitted;
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
public class OpenSourceWelcomeWizardEventListener {

    private static final String BI_EVENT_TYPE = "opik_os_welcome_wizard_submitted";

    private final @NonNull BiEventService biEventService;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull OpikConfiguration config;

    @Subscribe
    public void onOpenSourceWelcomeWizardSubmitted(OpenSourceWelcomeWizardSubmitted event) {
        if (!config.getUsageReport().isEnabled()) {
            return;
        }

        sendBiEvent(event);
    }

    private void sendBiEvent(OpenSourceWelcomeWizardSubmitted event) {
        try {
            // Check if this is an empty submission (dismissed without providing data)
            boolean isEmpty = event.getEmail() == null
                    && event.getRole() == null
                    && (event.getIntegrations() == null || event.getIntegrations().isEmpty())
                    && event.getJoinBetaProgram() == null;

            if (isEmpty) {
                log.info("Skipping BI event for empty OSS welcome wizard submission (dismissed) for workspace: '{}'",
                        event.getWorkspaceId());
                return;
            }

            var anonymousId = usageReportService.getAnonymousId().orElse(null);
            if (anonymousId == null) {
                log.warn("Anonymous ID not available, skipping BI event for OSS welcome wizard");
                return;
            }

            Map<String, String> eventProperties = new HashMap<>();
            eventProperties.put("opik_app_version", config.getMetadata().getVersion());
            eventProperties.put("date", Instant.now().toString());

            if (event.getEmail() != null) {
                eventProperties.put("email", event.getEmail());
            }
            if (event.getRole() != null) {
                eventProperties.put("role", event.getRole());
            }
            if (event.getIntegrations() != null && !event.getIntegrations().isEmpty()) {
                eventProperties.put("integrations", String.join(",", event.getIntegrations()));
                eventProperties.put("integrations_count", String.valueOf(event.getIntegrations().size()));
            }
            if (event.getJoinBetaProgram() != null) {
                eventProperties.put("join_beta_program", String.valueOf(event.getJoinBetaProgram()));
            }

            biEventService.reportEvent(anonymousId, BI_EVENT_TYPE, BI_EVENT_TYPE, eventProperties);
            log.info("BI event sent for OSS welcome wizard submission for workspace: '{}'",
                    event.getWorkspaceId());
        } catch (Exception e) {
            log.error("Failed to send BI event for OSS welcome wizard for workspace: '{}'", event.getWorkspaceId(),
                    e);
        }
    }
}
