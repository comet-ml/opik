package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record ContextInfoHolder(@NonNull UriInfo uriInfo,
        @NonNull String method) {
}
