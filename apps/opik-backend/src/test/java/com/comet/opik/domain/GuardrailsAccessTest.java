package com.comet.opik.domain;

import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardrailsAccessTest {

    private final WorkspaceFeaturesService workspaceFeatures = Mockito.mock(WorkspaceFeaturesService.class);
    private final RequestContext requestContext = RequestContext.builder().workspaceId("workspace-id").build();

    private GuardrailsAccess access(boolean toggleEnabled) {
        var configuration = new OpikConfiguration();
        configuration.getServiceToggles().setGuardrailsValidationsEnabled(toggleEnabled);
        return new GuardrailsAccess(new FeatureFlags(configuration), () -> requestContext, workspaceFeatures);
    }

    @Test
    void disabledToggleIsForbiddenWithoutAskingForTheOrganizationFeature() {
        assertThatThrownBy(() -> access(false).check())
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Guardrails validation service is not enabled");
        verifyNoInteractions(workspaceFeatures);
    }

    @Test
    void organizationWithoutGuardrailsIsForbidden() {
        when(workspaceFeatures.isEnabled(requestContext, WorkspaceFeature.GUARDRAILS)).thenReturn(false);

        assertThatThrownBy(() -> access(true).check())
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(GuardrailsAccess.NOT_ENABLED_MESSAGE);
    }

    @Test
    void enabledToggleAndOrganizationPass() {
        when(workspaceFeatures.isEnabled(requestContext, WorkspaceFeature.GUARDRAILS)).thenReturn(true);

        assertThatCode(() -> access(true).check()).doesNotThrowAnyException();
    }
}
