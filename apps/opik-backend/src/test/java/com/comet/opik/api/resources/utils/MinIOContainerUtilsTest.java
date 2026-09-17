package com.comet.opik.api.resources.utils;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

class MinIOContainerUtilsTest {

    @Test
    void newMinIOContainer_isConfiguredWithCloudPiratesImageAndExposedPort() {
        GenericContainer<?> container = MinIOContainerUtils.newMinIOContainer();

        assertThat(container.getDockerImageName())
                .isEqualTo("docker.io/cloudpirates/image-minio:RELEASE.2025-10-15T17-29-55Z-hardened");
        assertThat(container.getExposedPorts()).containsExactly(9000);
    }
}
