package com.comet.opik.api.view;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A page of a view's versions, newest first")
public record ViewVersionPage(
        List<Item> content,
        int page,
        @Schema(description = "Number of versions in this page") int size,
        long total) implements Page<ViewVersionPage.Item> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "ViewVersionSummary", description = "A version of a view without its HTML")
    public record Item(
            int version,
            ViewAuthor author,
            @Nullable @Schema(description = "Change summary; omitted when the author gave none") String summary,
            Instant createdAt,
            String createdBy) {
    }
}
