package com.comet.opik.api.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WidgetLayout(
        @JsonView( {
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotBlank String id,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Min(0) Integer x,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Min(0) Integer y,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Min(1) Integer w,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Min(1) Integer h,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull WidgetType type,

        @JsonView({
                Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Valid WidgetConfig config){
}
