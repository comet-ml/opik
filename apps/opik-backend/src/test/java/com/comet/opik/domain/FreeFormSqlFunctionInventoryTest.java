package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Free-form SQL ClickHouse function inventory")
class FreeFormSqlFunctionInventoryTest {

    private static final String SNAPSHOT = "free-form-sql/system-functions.tsv";
    private static final String HEADER = "name\talias_to\tis_aggregate\tcase_insensitive\torigin";
    private static final String FUNCTION_INVENTORY = """
            SELECT name, alias_to, is_aggregate, case_insensitive, origin
            FROM system.functions
            ORDER BY name, alias_to, is_aggregate, case_insensitive, origin
            FORMAT TSVRaw
            """;

    @Test
    @DisplayName("a ClickHouse image change requires review of every registered function and alias")
    void registeredFunctionsMatchReviewedSnapshot() throws Exception {
        var snapshot = FreeFormSqlFunctionInventoryTest.class.getClassLoader().getResourceAsStream(SNAPSHOT);
        assertThat(snapshot).as("reviewed function snapshot").isNotNull();
        try (snapshot; var clickHouse = ClickHouseContainerUtils.newClickHouseContainer()) {
            var expected = new String(snapshot.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertThat(expected.get(1)).isEqualTo(HEADER);

            clickHouse.start();
            assertThat("# " + clickHouse.getDockerImageName()).isEqualTo(expected.getFirst());
            var result = clickHouse.execInContainer("clickhouse", "client", "--query", FUNCTION_INVENTORY);
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();

            var observed = result.getStdout().lines().toList();
            var reviewed = expected.subList(2, expected.size());
            var added = new TreeSet<>(observed);
            added.removeAll(reviewed);
            var removed = new TreeSet<>(reviewed);
            removed.removeAll(observed);
            assertThat(added).as("new or changed functions requiring review").isEmpty();
            assertThat(removed).as("removed or changed functions requiring review").isEmpty();
            assertThat(observed).hasSameSizeAs(reviewed);
        }
    }
}
