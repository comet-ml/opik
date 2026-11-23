package com.comet.opik.api.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "An immutable version of a view")
public record ViewVersion(
        UUID viewId,
        int version,
        ViewAuthor author,
        @Nullable String summary,
        ViewType type,
        @Schema(description = "HTML contract version the view was written against") int engineVersion,
        Map<String, ViewInput> inputs,
        @Nullable @Schema(description = "Libraries the host loads before the view runs; omitted when it uses none") Set<String> libraries,
        String html,
        Instant createdAt,
        String createdBy) {

    public static final int MAX_HTML_LENGTH = 1_000_000;
    public static final String INPUT_NAME_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$";
    public static final String LIBRARY_NAME_PATTERN = "^[a-z][a-z0-9-]*$";
}
