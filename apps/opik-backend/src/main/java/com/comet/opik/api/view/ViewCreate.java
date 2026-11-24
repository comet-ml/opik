package com.comet.opik.api.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Map;
import java.util.Set;

import static com.comet.opik.api.view.ViewVersion.INPUT_NAME_PATTERN;
import static com.comet.opik.api.view.ViewVersion.LIBRARY_NAME_PATTERN;
import static com.comet.opik.api.view.ViewVersion.MAX_HTML_LENGTH;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ViewCreate(
        @NotNull @Schema(description = "The content area the view is written for; fixed after creation") ViewArea area,
        @NotBlank @Size(max = 150) String name,
        @Nullable @Size(max = 1000) String description,
        @Nullable @Size(max = 500) @Schema(description = "What the first version shows, one line") String summary,
        @Nullable @Schema(description = "Who produced the version: user (default), ollie or agent. Informational only") ViewAuthor author,
        @NotNull @Schema(description = "View type") ViewType type,
        @NotNull @Min(1) @Schema(description = "HTML contract version the view was written against") Integer engineVersion,
        @Nullable @Size(max = 10) @Schema(description = "Libraries the host loads before the view runs; none when omitted") Set<@NotNull @Pattern(regexp = LIBRARY_NAME_PATTERN) @Size(max = 50) String> libraries,
        @NotEmpty @Size(max = 20) @Valid @Schema(description = "Input name to data source, one or several; the view receives one data object with a key per input") Map<@NotNull @Pattern(regexp = INPUT_NAME_PATTERN) @Size(max = 100) String, @NotNull @Valid ViewInput> inputs,
        @NotBlank @Size(max = MAX_HTML_LENGTH) @Schema(description = "The view: HTML, CSS and script rendered in a sandboxed iframe") String html) {
}
