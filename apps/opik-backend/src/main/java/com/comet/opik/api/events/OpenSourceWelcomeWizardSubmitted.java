package com.comet.opik.api.events;

import java.util.List;

public record OpenSourceWelcomeWizardSubmitted(
        String workspaceId,
        String email,
        String role,
        List<String> integrations,
        Boolean joinBetaProgram) {
}
