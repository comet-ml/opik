package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Tag creation request")
public record TagCreate(
        @JsonView( {
                Tag.View.Write.class}) @NotBlank @Size(max = 100) String name,
        @JsonView({Tag.View.Write.class}) @Nullable @Size(max = 500) String description){
}