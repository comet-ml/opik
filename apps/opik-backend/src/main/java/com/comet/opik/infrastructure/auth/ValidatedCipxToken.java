package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

/**
 * The caller attributes a CIPX device token resolves to, as returned by the cost-api validator.
 * {@code deviceId} is the server-assigned machine identity; the rest mirror what any other
 * credential resolves to.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValidatedCipxToken(
        String userName,
        String workspaceId,
        String workspaceName,
        String deviceId) {

    ValidatedToken toValidatedToken() {
        return ValidatedToken.builder()
                .userName(userName)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .build();
    }
}
