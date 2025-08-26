package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Annotation(
        @JsonView({Annotation.View.Public.class})
        @Schema(description = "Annotation ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID id,

        @JsonView({Annotation.View.Public.class})
        @Schema(description = "Queue item ID", accessMode = Schema.AccessMode.READ_ONLY)
        UUID queueItemId,

        @JsonView({Annotation.View.Public.class})
        @Schema(description = "SME user ID", accessMode = Schema.AccessMode.READ_ONLY)
        String smeId,

        @JsonView({Annotation.View.Public.class, Annotation.View.Write.class})
        @Schema(description = "Annotation metrics as key-value pairs", 
                example = "{\"rating\": 4, \"categories\": [\"helpful\", \"accurate\"], \"thumbs\": \"up\"}")
        @NotNull
        Map<String, Object> metrics,

        @JsonView({Annotation.View.Public.class, Annotation.View.Write.class})
        @Schema(description = "Optional comment from SME", example = "Good response but could be more concise")
        String comment,

        @JsonView({Annotation.View.Public.class})
        @Schema(description = "Annotation creation timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant createdAt,

        @JsonView({Annotation.View.Public.class})
        @Schema(description = "Annotation last update timestamp", accessMode = Schema.AccessMode.READ_ONLY)
        Instant updatedAt
) {

    public static class View {
        public static class Public {}
        public static class Write {}
    }
}