package com.comet.opik.api.resources.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsentRequest(
        String clientId,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String resource,
        String state,
        String workspaceId,
        String workspaceName,
        String csrf) {
}
