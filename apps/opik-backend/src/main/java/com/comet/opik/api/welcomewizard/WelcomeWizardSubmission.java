package com.comet.opik.api.welcomewizard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WelcomeWizardSubmission(
        @Size(max = 100, message = "Role cannot exceed 100 characters") @Schema(description = "Optional user role") String role,

        @Schema(description = "List of integrations the user selected") List<String> integrations,

        @Email(message = "Invalid email format") @Size(max = 255, message = "Email cannot exceed 255 characters") @Schema(description = "Optional user email") String email,

        @Schema(description = "Whether user wants to join beta programs") Boolean joinBetaProgram) {
}
