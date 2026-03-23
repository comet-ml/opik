package com.comet.opik.api.retention;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RetentionRule(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String workspaceId,
        @JsonView({View.Public.class, View.Write.class}) UUID projectId,
        @JsonView({
                View.Write.class}) @Nullable @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "Set to true to create an organization-level rule") Boolean organizationLevel,
        @JsonView({
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Computed from projectId and organizationLevel") RetentionLevel level,
        @JsonView({View.Public.class, View.Write.class}) @NotNull RetentionPeriod retention,
        @JsonView({View.Public.class, View.Write.class}) Boolean applyToPast,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Boolean enabled,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetentionRulePage(
            @JsonView( {
                    View.Public.class}) List<RetentionRule> content,
            @JsonView({View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total) implements Page<RetentionRule>{

        public static RetentionRulePage empty(int page) {
            return new RetentionRulePage(List.of(), page, 0, 0);
        }
    }
}
