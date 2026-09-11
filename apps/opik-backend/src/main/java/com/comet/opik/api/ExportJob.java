package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExportJob(
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) ExportParams params,
        @Nullable @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String resourceName,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) ExportStatus status,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String filePath,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String errorMessage,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant expiresAt,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant viewedAt,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                ExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    /**
     * Derived from {@link #params()} rather than stored beside it, so the discriminator can never disagree with the
     * payload it describes.
     */
    @JsonProperty("export_type")
    @JsonView(ExportJob.View.Public.class)
    public String exportType() {
        return params == null ? null : params.exportType();
    }

    /**
     * Dedupe key for the job, derived so it always matches the params actually persisted.
     */
    @JsonIgnore
    public String paramsHash() {
        return params == null ? null : params.canonicalHash();
    }

    public static class View {
        public static class Public {
        }
    }
}
