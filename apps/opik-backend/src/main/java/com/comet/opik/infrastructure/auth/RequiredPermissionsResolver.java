package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.container.ResourceInfo;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves {@link RequiredPermissions} from a matched JAX-RS resource to a list of permission strings.
 */
public final class RequiredPermissionsResolver {

    private RequiredPermissionsResolver() {
    }

    public static List<String> getRequiredPermissions(ResourceInfo resourceInfo) {
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null) {
            return List.of();
        }
        RequiredPermissions ann = resourceInfo.getResourceMethod().getAnnotation(RequiredPermissions.class);
        if (ann == null || ann.value().length == 0) {
            return List.of();
        }
        return Arrays.stream(ann.value())
                .map(WorkspaceUserPermission::getValue)
                .collect(Collectors.toList());
    }
}
