package com.comet.opik.domain.mcpoauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.Set;

// RFC 7591 §2 client metadata; unknown fields are ignored.
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientRegistrationRequest(String clientName, Set<String> redirectUris, String logoUri) {
}
