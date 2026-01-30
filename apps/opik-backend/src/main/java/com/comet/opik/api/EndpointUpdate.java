package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EndpointUpdate(
        @JsonView( {
                Endpoint.View.Write.class}) @Nullable @Size(max = 255) String name,
        @JsonView({Endpoint.View.Write.class}) @Nullable @Size(max = 2048) String url,
        @JsonView({Endpoint.View.Write.class}) @Nullable String secret,
        @JsonView({Endpoint.View.Write.class}) @Nullable String schemaJson){
}
