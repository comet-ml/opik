package com.comet.opik.infrastructure.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

/**
 * The caller attributes a CIPX device token resolves to, as returned by the cost-api validator.
 * {@code deviceId} is the server-assigned machine identity, and {@code userName} is the device's
 * MDM-provisioned email address rather than a Comet username -- it becomes {@code traces.created_by}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValidatedCipxToken(
        String userName,
        String workspaceId,
        String workspaceName,
        String deviceId) {
}
