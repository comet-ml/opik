package com.comet.opik.api.resources.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsentRequest(
        @NotBlank String clientId,
        @NotBlank String redirectUri,
        @NotBlank String codeChallenge,
        @NotBlank @Pattern(regexp = CODE_CHALLENGE_METHOD_S256) String codeChallengeMethod,
        @NotBlank String resource,
        String state,
        String workspaceId,
        String workspaceName,
        String csrf) {
}
