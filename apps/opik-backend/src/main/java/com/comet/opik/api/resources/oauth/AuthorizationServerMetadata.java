package com.comet.opik.api.resources.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(NON_NULL)
public record AuthorizationServerMetadata(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String revocationEndpoint,
        String registrationEndpoint,
        List<String> responseTypesSupported,
        List<String> grantTypesSupported,
        List<String> codeChallengeMethodsSupported,
        List<String> tokenEndpointAuthMethodsSupported) {
}
