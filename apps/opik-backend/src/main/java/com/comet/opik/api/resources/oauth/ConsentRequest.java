package com.comet.opik.api.resources.oauth;

import lombok.Builder;

@Builder(toBuilder = true)
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
