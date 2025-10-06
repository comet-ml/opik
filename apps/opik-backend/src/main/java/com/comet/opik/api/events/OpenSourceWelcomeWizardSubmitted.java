package com.comet.opik.api.events;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Builder(toBuilder = true)
@Jacksonized
public class OpenSourceWelcomeWizardSubmitted {

    @NonNull String workspaceId;
    String email;
    String role;
    List<String> integrations;
    Boolean joinBetaProgram;
}
