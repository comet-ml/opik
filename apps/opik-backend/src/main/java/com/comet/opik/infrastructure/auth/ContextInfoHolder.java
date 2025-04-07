package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.core.UriInfo;

public record ContextInfoHolder(UriInfo uriInfo,
        String method) {
}
