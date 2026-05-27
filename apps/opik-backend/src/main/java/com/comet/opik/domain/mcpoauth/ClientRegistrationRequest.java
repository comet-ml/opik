package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

import java.util.Set;

@Builder
public record ClientRegistrationRequest(String clientName, Set<String> redirectUris, String logoUri) {
}
