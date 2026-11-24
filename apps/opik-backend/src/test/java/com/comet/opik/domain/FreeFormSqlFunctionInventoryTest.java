package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@link FreeFormSqlForbiddenFunctions} reviewed against the ClickHouse functions CI runs. The inventory lists
 * every function and alias of the pinned version with its review decision: {@code allow} or {@code deny} for a
 * function, empty for an alias of a listed function, which takes that function's decision. CI runs the Alpine build of
 * the version the Helm chart pins.
 *
 * <p>On a ClickHouse upgrade the test fails and prints, as inventory lines, the functions and aliases the new version
 * adds or removes. Add each with a decision: {@code deny} for anything that discloses the account, the server or its
 * configuration, reads data outside the row policies, or has side effects. Add every function and alias the inventory
 * denies to {@link FreeFormSqlForbiddenFunctions}, then rename the file and {@link #VERSION} to the new version.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Free-form SQL ClickHouse function inventory")
class FreeFormSqlFunctionInventoryTest {

    private static final String VERSION = "26.3.62.3";
    private static final String INVENTORY = "/free-form-sql/clickhouse-functions-%s.tsv".formatted(VERSION);
    private static final String LIVE_FUNCTIONS = """
            SELECT name, alias_to, categories FROM system.functions ORDER BY name FORMAT TSVRaw
            """;

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer();

    private record Entry(String name, String aliasTo, String category, String decision) {

        /** Missing trailing fields read as empty, so a stripped trailing tab cannot shift a column. */
        static Entry parse(String line) {
            String[] fields = Arrays.copyOf(line.split("\t", -1), 4);
            return new Entry(fields[0], Objects.toString(fields[1], ""), Objects.toString(fields[2], ""),
                    Objects.toString(fields[3], ""));
        }

        boolean isAlias() {
            return !aliasTo.isEmpty();
        }

        /** The inventory line to add for a function or alias the reviewed version does not have. */
        String toInventoryLine() {
            return String.join("\t", name, aliasTo, category, "");
        }
    }

    @BeforeAll
    void setUpAll() {
        clickHouse.start();
    }

    @Test
    @DisplayName("the CI image runs the reviewed ClickHouse version")
    void ciImageRunsTheReviewedVersion() throws Exception {
        assertThat(query("SELECT version()")).containsExactly(VERSION);
    }

    @Test
    @DisplayName("the CI image has exactly the reviewed functions and aliases")
    void liveFunctionsMatchTheInventory() throws Exception {
        Map<String, Entry> live = query(LIVE_FUNCTIONS).stream().map(Entry::parse)
                .collect(Collectors.toMap(Entry::name, Function.identity()));
        Map<String, Entry> reviewed = inventory().stream()
                .collect(Collectors.toMap(Entry::name, Function.identity()));

        assertThat(live.values().stream()
                .filter(entry -> !reviewed.containsKey(entry.name())
                        || !reviewed.get(entry.name()).aliasTo().equals(entry.aliasTo()))
                .map(Entry::toInventoryLine).sorted())
                .as("functions and aliases to review and add to %s", INVENTORY)
                .isEmpty();
        assertThat(reviewed.keySet().stream().filter(name -> !live.containsKey(name)).sorted())
                .as("functions and aliases %s lists that this ClickHouse version no longer has", INVENTORY)
                .isEmpty();
    }

    @Test
    @DisplayName("every function has a decision, and the denied functions and aliases are exactly the forbidden ones")
    void decisionsMatchTheForbiddenFunctions() throws IOException {
        List<Entry> inventory = inventory();
        Map<String, Entry> functions = functionsByLowerName(inventory);

        assertThat(inventory.stream()
                .filter(entry -> entry.isAlias() && target(entry, functions) != null
                        ? !entry.decision().isEmpty()
                        : !Set.of("allow", "deny").contains(entry.decision()))
                .map(Entry::name))
                .as("a function needs allow or deny; an alias of a listed function none of its own")
                .isEmpty();

        Map<String, Set<String>> decisionsByLowerName = inventory.stream().collect(Collectors.groupingBy(
                entry -> entry.name().toLowerCase(Locale.ROOT),
                Collectors.mapping(entry -> decisionOf(entry, functions), Collectors.toSet())));

        assertThat(inventory.stream()
                .filter(entry -> decisionOf(entry, functions).equals("deny"))
                .map(entry -> entry.name().toLowerCase(Locale.ROOT))
                .filter(name -> !FreeFormSqlForbiddenFunctions.ALL.contains(name)))
                .as("functions and aliases the inventory denies but FreeFormSqlForbiddenFunctions does not list")
                .isEmpty();
        assertThat(FreeFormSqlForbiddenFunctions.ALL.stream()
                .filter(name -> !Set.of("deny").equals(decisionsByLowerName.get(name))))
                .as("FreeFormSqlForbiddenFunctions entries the inventory does not have, or does not deny")
                .isEmpty();
    }

    @Test
    @DisplayName("the denied names ClickHouse calls when written bare are exactly the bare names")
    void bareNamesMatchClickHouse() throws Exception {
        List<Entry> inventory = inventory();
        Map<String, Entry> functions = functionsByLowerName(inventory);
        List<String> denied = inventory.stream()
                .filter(entry -> decisionOf(entry, functions).equals("deny"))
                .map(Entry::name)
                .toList();
        // One statement per name; --ignore-error skips the names ClickHouse cannot resolve as identifiers.
        String probes = denied.stream()
                .map(name -> "SELECT '%s', toString(`%s`);".formatted(name, name))
                .collect(Collectors.joining("\n"));
        var result = clickHouse.execInContainer("clickhouse", "client", "--ignore-error", "--multiquery",
                "--query", probes);

        assertThat(result.getStdout().lines()
                .map(line -> line.split("\t", 2)[0].toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet()))
                .as("lower-cased denied names that resolve as bare identifiers")
                .isEqualTo(FreeFormSqlForbiddenFunctions.BARE_NAMES);
    }

    private static Map<String, Entry> functionsByLowerName(List<Entry> inventory) {
        return inventory.stream().filter(entry -> !entry.isAlias()).collect(Collectors.toMap(
                entry -> entry.name().toLowerCase(Locale.ROOT), Function.identity(), (first, second) -> first));
    }

    /**
     * An alias takes its target's decision, since the resolved query tree reports the canonical name. A target is
     * matched case-insensitively ({@code connection_id} names {@code connectionID}); an alias of a name the inventory
     * does not list, such as the combinator {@code groupArrayArray}, carries its own decision.
     */
    private static String decisionOf(Entry entry, Map<String, Entry> functions) {
        Entry target = entry.isAlias() ? target(entry, functions) : null;
        return target != null ? target.decision() : entry.decision();
    }

    private static Entry target(Entry alias, Map<String, Entry> functions) {
        return functions.get(alias.aliasTo().toLowerCase(Locale.ROOT));
    }

    private List<Entry> inventory() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream(INVENTORY), INVENTORY),
                StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.startsWith("#")).skip(1).map(Entry::parse).toList();
        }
    }

    private List<String> query(String sql) throws Exception {
        var result = clickHouse.execInContainer("clickhouse", "client", "--query", sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().lines().toList();
    }
}
