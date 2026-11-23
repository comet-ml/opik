package com.comet.opik.api.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A data source of a view; the host resolves it and passes the result under the input name")
public record ViewInput(
        @NotNull @Schema(description = "Source type") ViewInputKind kind,
        @NotBlank @Size(max = 150) @Schema(description = "The saved query to run") String queryId,
        @Nullable @Size(max = 200) @Schema(description = "The columns the author saw when it ran the query, name to type. Informational") Map<@NotBlank @Size(max = 255) String, @NotBlank @Size(max = 50) String> columns) {
}
