package com.comet.opik.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalWorkspacePermissionsServiceTest {

    @Test
    void testGetPermissionsReturnsDefaultUser() {
        var service = new LocalWorkspacePermissionsService();

        var result = service.getPermissions("any-api-key", "any-workspace");

        assertThat(result.userName()).isEqualTo(ProjectService.DEFAULT_USER);
        assertThat(result.workspaceName()).isEqualTo("any-workspace");
        assertThat(result.permissions()).isEmpty();
    }
}
