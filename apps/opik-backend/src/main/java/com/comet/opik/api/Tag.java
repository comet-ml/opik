package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Tag entity")
public record Tag(
        @JsonView( {
                View.Read.class, View.Write.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({View.Read.class, View.Write.class}) @NotBlank @Size(max = 100) String name,
        @JsonView({View.Read.class, View.Write.class}) @Nullable @Size(max = 500) String description,
        @JsonView({View.Read.class,
                View.Write.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String workspace_id,
        @JsonView({View.Read.class,
                View.Write.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant created_at,
        @JsonView({View.Read.class,
                View.Write.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant updated_at){
    public interface View {
        interface Read {
        }
        interface Write {
        }
        interface Updatable {
        }
    }
}