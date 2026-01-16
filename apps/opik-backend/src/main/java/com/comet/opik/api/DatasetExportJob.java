package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetExportJob(
        @JsonView( {
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) DatasetExportStatus status,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String filePath,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String errorMessage,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant expiresAt,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant viewedAt,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                DatasetExportJob.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static class View {
        public static class Public {
        }
    }
}
