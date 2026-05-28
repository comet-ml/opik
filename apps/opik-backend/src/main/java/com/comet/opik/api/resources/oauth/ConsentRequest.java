package com.comet.opik.api.resources.oauth;

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
