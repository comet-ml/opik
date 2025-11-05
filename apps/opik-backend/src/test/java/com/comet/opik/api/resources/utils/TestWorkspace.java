package com.comet.opik.api.resources.utils;

/**
 * Record for holding test workspace configuration data used across test classes.
 * Encapsulates workspace-related test data like workspace name, ID, API key, and project name.
 *
 * @param workspaceName the name of the test workspace
 * @param workspaceId the unique identifier of the test workspace
 * @param apiKey the API key for the test workspace
 * @param projectName the name of the test project
 */
public record TestWorkspace(
        String workspaceName,
        String workspaceId,
        String apiKey,
        String projectName) {
}
