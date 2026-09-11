package com.comet.opik.api.resources.oauth;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record AuthorizeRequest(
        @NonNull String clientId,
        @NonNull String redirectUri,
        String responseType,
        String codeChallenge,
        String codeChallengeMethod,
        String resource,
        String state,
        String rawQuery) {
}
