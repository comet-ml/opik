package com.comet.opik.api.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WidgetConfig(
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) 
        @NotBlank 
        String title,
        
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) 
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") 
        String dataSource,
        
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) 
        @Nullable 
        Map<String, Object> queryParams,
        
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) 
        @Nullable 
        Map<String, Object> chartOptions,
        
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) 
        @Nullable @Min(5) 
        Integer refreshInterval
) {}
