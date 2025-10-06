package com.comet.opik.api.opensourcewelcomewizard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenSourceWelcomeWizardTracking(
        @JsonProperty("workspace_id") @Schema(description = "The workspace identifier") String workspaceId,

        @JsonProperty("completed") @Schema(description = "Whether the welcome wizard was completed") Boolean completed,

        @JsonProperty("email") @Schema(description = "Optional user email") String email,

        @JsonProperty("role") @Schema(description = "Optional user role") String role,

        @JsonProperty("integrations") @Schema(description = "List of integrations the user selected") List<String> integrations,

        @JsonProperty("join_beta_program") @Schema(description = "Whether user wants to join beta programs") Boolean joinBetaProgram,

        @JsonProperty("submitted_at") @Schema(description = "Timestamp when the wizard was submitted") Instant submittedAt,

        @JsonView( {
                OpenSourceWelcomeWizardView.Internal.class}) @JsonProperty("created_at") @Schema(description = "Created at timestamp") Instant createdAt,

        @JsonView({
                OpenSourceWelcomeWizardView.Internal.class}) @JsonProperty("created_by") @Schema(description = "Created by user") String createdBy,

        @JsonView({
                OpenSourceWelcomeWizardView.Internal.class}) @JsonProperty("last_updated_at") @Schema(description = "Last updated at timestamp") Instant lastUpdatedAt,

        @JsonView({
                OpenSourceWelcomeWizardView.Internal.class}) @JsonProperty("last_updated_by") @Schema(description = "Last updated by user") String lastUpdatedBy){
}
