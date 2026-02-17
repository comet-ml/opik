package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.container.ResourceInfo;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves {@link RequiredPermissions} from a matched JAX-RS resource to a list of permission strings.
 */
@UtilityClass
public class RequiredPermissionsResolver {

    public List<String> getRequiredPermissions(ResourceInfo resourceInfo) {
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null) {
            return List.of();
        }
        RequiredPermissions ann = resourceInfo.getResourceMethod().getAnnotation(RequiredPermissions.class);
        WorkspaceUserPermission[] value = ann != null ? ann.value() : null;
        if (value == null || value.length == 0) {
            return List.of();
        }
        return Arrays.stream(value)
                .map(WorkspaceUserPermission::getValue)
                .collect(Collectors.toList());
    }
}
