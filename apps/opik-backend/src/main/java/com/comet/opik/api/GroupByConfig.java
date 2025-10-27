package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.jdbi.v3.core.mapper.reflect.ColumnName;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GroupByConfig(
        @JsonView( {
                GroupByConfig.View.Public.class,
                GroupByConfig.View.Write.class}) @Size(max = 100) @ColumnName("group_by_field") String field,
        @JsonView({GroupByConfig.View.Public.class,
                GroupByConfig.View.Write.class}) @ColumnName("group_by_type") GroupByType type,
        @JsonView({GroupByConfig.View.Public.class,
                GroupByConfig.View.Write.class}) @Min(1) @Max(100) @Schema(defaultValue = "5") @ColumnName("limit_top_n") Integer limitTopN){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
