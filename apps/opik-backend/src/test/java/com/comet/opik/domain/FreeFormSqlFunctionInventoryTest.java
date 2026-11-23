package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the registered ClickHouse functions and aliases in the images used by CI and production. Backend tests use
 * the Alpine image through {@link ClickHouseContainerUtils}, while production uses the standard image. Both images
 * are pinned to the same ClickHouse version, but their different packaging could change the available functions.
 * Comparing their live inventories also makes a CI image upgrade require review against the production image.
 */
@DisplayName("Free-form SQL ClickHouse function inventory")
class FreeFormSqlFunctionInventoryTest {

    // Update only after reviewing the function and alias differences reported by this test.
    private static final String REVIEWED_CLICKHOUSE_VERSION = "26.3.62.3";
    private static final String REVIEWED_CI_IMAGE = "clickhouse/clickhouse-server:" + REVIEWED_CLICKHOUSE_VERSION
            + "-alpine";
    private static final String REVIEWED_PRODUCTION_IMAGE = "clickhouse/clickhouse-server:"
            + REVIEWED_CLICKHOUSE_VERSION;
    private static final String FUNCTION_NAMES = """
            SELECT if(alias_to = '', name, concat(name, ' -> ', alias_to)) AS function_name
            FROM system.functions
            ORDER BY function_name
            FORMAT TSVRaw
            """;

    @Test
    @DisplayName("the CI image has only the functions and aliases of the reviewed production image")
    void registeredFunctionsMatchReviewedVersion() throws Exception {
        try (var current = ClickHouseContainerUtils.newClickHouseContainer();
                var reviewed = new ClickHouseContainer(DockerImageName.parse(REVIEWED_PRODUCTION_IMAGE))) {
            reviewed.start();
            current.start();

            List<String> currentFunctions = functionNames(current);
            List<String> reviewedFunctions = functionNames(reviewed);
            var added = new TreeSet<>(currentFunctions);
            added.removeAll(reviewedFunctions);
            var removed = new TreeSet<>(reviewedFunctions);
            removed.removeAll(currentFunctions);

            assertThat(added).as("new or changed functions and aliases requiring review").isEmpty();
            assertThat(removed).as("removed or changed functions and aliases requiring review").isEmpty();
            assertThat(currentFunctions).containsExactlyElementsOf(reviewedFunctions);
            assertThat(current.getDockerImageName()).as("CI ClickHouse image requiring review")
                    .isEqualTo(REVIEWED_CI_IMAGE);
        }
    }

    private static List<String> functionNames(ClickHouseContainer clickHouse) throws Exception {
        var result = clickHouse.execInContainer("clickhouse", "client", "--query", FUNCTION_NAMES);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().lines().sorted().toList();
    }
}
