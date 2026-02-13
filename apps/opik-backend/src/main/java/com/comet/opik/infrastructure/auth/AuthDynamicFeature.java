package com.comet.opik.infrastructure.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Registers {@link AuthFilter} per resource so it runs after request matching.
 * Has {@link ResourceInfo} accessible in cases when filter runs outside the JAX-RS container (e.g. filter unit tests)
 */
@Provider
public class AuthDynamicFeature implements DynamicFeature {

    public static final String REQUIRED_PERMISSIONS_PROPERTY = "auth.requiredPermissions";

    private final AuthFilter authFilter;

    @Inject
    public AuthDynamicFeature(AuthFilter authFilter) {
        this.authFilter = authFilter;
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        List<String> permissions = RequiredPermissionsResolver.getRequiredPermissions(resourceInfo);
        context.register((ContainerRequestFilter) requestContext -> {
            requestContext.setProperty(REQUIRED_PERMISSIONS_PROPERTY, permissions);
            authFilter.filter(requestContext);
        });
    }
}
