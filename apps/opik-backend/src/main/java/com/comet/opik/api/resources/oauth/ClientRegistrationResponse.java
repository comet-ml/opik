package com.comet.opik.api.resources.oauth;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.Set;

@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClientRegistrationResponse(
        String clientId,
        Long clientIdIssuedAt,
        String clientName,
        String logoUri,
        Set<String> redirectUris,
        String tokenEndpointAuthMethod,
        List<String> grantTypes,
        List<String> responseTypes) {
}
