package com.comet.opik;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Guards the guard.
 * <p>
 * A test that blocks forever used to cost the entire 10-minute Unit Tests job and surfaced only as
 * "job cancelled" -- finding the culprit meant reading raw job logs for the class that printed
 * "Running ..." with no matching result line. The safeguard against that now lives in
 * junit-platform.properties plus a per-job -D override, either of which a rename or a scope change
 * could silently disable while the suite still passed green.
 * <p>
 * The shipped default and the CLI override are asserted separately and unconditionally. An earlier
 * version preferred the override when present, which meant CI -- where the override is always set --
 * never validated the shipped default at all, and the file could drift to any value undetected.
 * <p>
 * These tests are deliberately fast and cannot hang: the blocking case is bounded preemptively.
 */
@DisplayName("Test Timeout Guard")
class TestTimeoutGuardTest {

    private static final String TIMEOUT_PROPERTY = "junit.jupiter.execution.timeout.testable.method.default";

    /** Job walls from discover-backend-tests.sh: UNIT_TIMEOUT and INTEGRATION_TIMEOUT. */
    private static final Duration UNIT_JOB_WALL = Duration.ofMinutes(10);
    private static final Duration INTEGRATION_JOB_WALL = Duration.ofMinutes(20);

    /** CI runs -Dsurefire.rerunFailingTestsCount=3, and a timeout is an ordinary failure to Surefire. */
    private static final int ATTEMPTS_PER_HANG = 4;

    /**
     * JUnit's timeout grammar: a number, optional space, optional unit of ns/us/ms/s/m/h/d, parsed
     * case-insensitively. A bare number means seconds. Anything else must be rejected rather than
     * silently coerced -- the whole point of this class is that a wrong bound cannot pass unnoticed.
     */
    private static final Pattern TIMEOUT_GRAMMAR = Pattern.compile("(?i)^(\\d+)\\s*(ns|μs|us|ms|s|m|h|d)?$");

    @Test
    @DisplayName("a thread blocked forever is interrupted and reported, never left to hang the job")
    void blockedTestIsInterruptedRatherThanHanging() {
        var neverCountedDown = new CountDownLatch(1);

        // Preemptive timeout is the same mechanism JUnit applies to @Test methods via the
        // timeout.testable.method.default property: it abandons the blocked thread and fails.
        Executable blocksForever = () -> neverCountedDown.await();
        var failure = assertThrows(AssertionError.class,
                () -> assertTimeoutPreemptively(Duration.ofMillis(200), blocksForever));

        assertThat(failure).hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("the shipped default survives CI's retry budget, regardless of any -D override")
    void shippedDefaultFitsRetryBudget() {
        // Read the file directly, never System.getProperty: in CI the override is always set, so
        // consulting it here would mean the shipped default was never actually checked.
        var shipped = loadProperties().getProperty(TIMEOUT_PROPERTY);

        assertRetryBudgetFits(shipped, UNIT_JOB_WALL, "shipped default");
    }

    @Test
    @DisplayName("an active -D override also survives the retry budget for its tier")
    void overrideFitsRetryBudget() {
        var override = System.getProperty(TIMEOUT_PROPERTY);

        // Under CI the workflow always passes -D, so its absence means the override was removed or
        // renamed and every job silently fell back to the shipped default. Skipping quietly here
        // would let the guard stay green through exactly that regression. Locally there is no
        // override to check, and the shipped default is asserted unconditionally above.
        if (runningInCi()) {
            assertThat(override)
                    .as("CI must pass -D%s (see the mvn step in backend_tests.yml)", TIMEOUT_PROPERTY)
                    .isNotBlank();
        } else if (override == null) {
            return;
        }

        // The override is per-tier and the tier is not knowable from inside the JVM, so hold it to
        // the most generous wall. Each tier's exact value is pinned by ciMatrixWiresBothTiers.
        assertRetryBudgetFits(override, INTEGRATION_JOB_WALL, "-D override");
    }

    @Test
    @DisplayName("the timeout is scoped to testable methods so container startup stays exempt")
    void timeoutIsScopedToTestableMethods() {
        // A blanket junit.jupiter.execution.timeout.default would also bound @BeforeAll/@BeforeEach,
        // making slow, variable Testcontainers startup the thing that fails instead of the test.
        assertThat(loadProperties().stringPropertyNames())
                .contains(TIMEOUT_PROPERTY)
                .doesNotContain("junit.jupiter.execution.timeout.default");
    }

    @ParameterizedTest(name = "{0} -> {1}s")
    @CsvSource({"30s, 30", "45, 45", "2m, 120", "4m, 240", "1h, 3600", "500ms, 0", "2 m, 120", "2M, 120"})
    @DisplayName("timeout values are parsed per JUnit's grammar, not by stripping the unit")
    void parsesJUnitTimeoutGrammar(String value, long expectedSeconds) {
        assertThat(toSeconds(value)).isEqualTo(expectedSeconds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2x", "abc", "m", "2mm", "-5m", ""})
    @DisplayName("values outside JUnit's grammar are rejected, never coerced to a passing number")
    void rejectsValuesOutsideGrammar(String value) {
        assertThatThrownBy(() -> toSeconds(value)).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"106751991167300d", "9223372036854775807s", "9223372036854775807us"})
    @DisplayName("absurd values fail closed rather than overflowing into a passing budget")
    void failsClosedOnOverflow(String value) {
        // These are grammar-valid, so parsing accepts them; the budget multiplication is where they
        // used to wrap negative and satisfy isLessThan(wall) -- the assertion meant to reject
        // oversized bounds accepted the most oversized ones of all.
        assertThatThrownBy(() -> assertRetryBudgetFits(value, UNIT_JOB_WALL, "overflow probe"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("the CI matrix wires a budget-safe timeout into every job, end to end")
    void ciMatrixWiresBothTiers() {
        // Pinning the shell variables alone is not enough: the values only reach JUnit if the
        // matrix emits them AND the workflow passes them through. Any link can be renamed or
        // dropped independently, so assert the whole chain -- script variable, emitted matrix
        // entry, and the -D on the mvn step.
        var script = readDiscoveryScript();
        var workflow = readWorkflow();

        assertThat(workflow)
                .as("the mvn step must pass matrix.testTimeout as -D%s", TIMEOUT_PROPERTY)
                .contains("-D" + TIMEOUT_PROPERTY + "=\"${{ matrix.testTimeout }}\"");
        assertThat(workflow)
                .as("the job wall must come from the matrix, matching the tiers asserted here")
                .contains("timeout-minutes: ${{ matrix.timeout }}");

        assertTierPinned(script, "UNIT_TIMEOUT", "UNIT_TEST_TIMEOUT", UNIT_JOB_WALL);
        assertTierPinned(script, "INTEGRATION_TIMEOUT", "INTEGRATION_TEST_TIMEOUT", INTEGRATION_JOB_WALL);

        // Both emitted entry shapes must pair the wall with the per-test bound. A job missing the
        // testTimeout key would render it as the empty string and fall back to the shipped default.
        assertThat(script)
                .as("the unit matrix entry must set both timeout and testTimeout")
                .contains("\\\"timeout\\\":$UNIT_TIMEOUT,\\\"testTimeout\\\":\\\"$UNIT_TEST_TIMEOUT\\\"");
        assertThat(script)
                .as("the integration matrix entry must set both timeout and testTimeout")
                .contains("\\\"timeout\\\":$INTEGRATION_TIMEOUT,\\\"testTimeout\\\":\\\"$INTEGRATION_TEST_TIMEOUT\\\"");
    }

    private static void assertTierPinned(String script, String wallVar, String testVar, Duration expectedWall) {
        var wallMinutes = Long.parseLong(matchOne(script, wallVar + "=(\\d+)"));
        assertThat(Duration.ofMinutes(wallMinutes))
                .as("%s must match the wall this test asserts against", wallVar)
                .isEqualTo(expectedWall);

        assertRetryBudgetFits(matchOne(script, testVar + "=(\\S+)"), expectedWall, testVar);
    }

    private static void assertRetryBudgetFits(String value, Duration wall, String description) {
        // Checked arithmetic, and fail closed on overflow: with plain long math an absurd but
        // grammar-valid bound (e.g. 106751991167300d) wrapped negative and satisfied
        // isLessThan(wall), so the assertion designed to reject oversized timeouts accepted the
        // most oversized ones of all.
        long budget;
        try {
            budget = Math.multiplyExact(toSeconds(value), (long) ATTEMPTS_PER_HANG);
        } catch (ArithmeticException overflow) {
            throw new AssertionError(
                    "'%s' (%s) is absurdly large: %d attempts overflow a long, so it cannot fit inside the %s job wall"
                            .formatted(value, description, ATTEMPTS_PER_HANG, wall),
                    overflow);
        }

        // 4 attempts at the bound must still leave room for compilation and suite overhead,
        // otherwise CI cancels the job before Surefire can publish the named failure -- losing
        // exactly the diagnostic the timeout exists to provide.
        assertThat(budget)
                .as("%d attempts at '%s' (%s) must fit inside the %s job wall",
                        ATTEMPTS_PER_HANG, value, description, wall)
                .isPositive()
                .isLessThan(wall.toSeconds());
    }

    private static String matchOne(String haystack, String regex) {
        var matcher = Pattern.compile("^" + regex + "\\s*$", Pattern.MULTILINE).matcher(haystack);
        assertThat(matcher.find()).as("discovery script must define %s", regex).isTrue();
        return matcher.group(1);
    }

    private static boolean runningInCi() {
        // GITHUB_ACTIONS is set by the runner itself, so it cannot drift out of sync with the
        // workflow the way a hand-maintained flag would.
        return Boolean.parseBoolean(System.getenv("GITHUB_ACTIONS"));
    }

    private static String readWorkflow() {
        return readRepoFile("../../.github/workflows/backend_tests.yml");
    }

    private static String readDiscoveryScript() {
        return readRepoFile("../../.github/scripts/discover-backend-tests.sh");
    }

    private static String readRepoFile(String relativeToModule) {
        var path = java.nio.file.Path.of("").toAbsolutePath().resolve(relativeToModule).normalize();
        assertThat(path).as("%s must exist", relativeToModule).exists();
        try {
            return java.nio.file.Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Properties loadProperties() {
        var properties = new Properties();
        try (var in = TestTimeoutGuardTest.class.getResourceAsStream("/junit-platform.properties")) {
            assertThat(in).as("junit-platform.properties must be on the test classpath").isNotNull();
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    /**
     * Converts a JUnit timeout value to seconds, rejecting anything outside the documented grammar.
     * Rejecting matters more than converting: an unrecognised value that silently became a small
     * number would let a catastrophic bound sail past the retry-budget assertions above.
     */
    private static long toSeconds(String value) {
        assertThat(value).as("per-test timeout must be configured").isNotBlank();

        var matcher = TIMEOUT_GRAMMAR.matcher(value.trim());
        assertThat(matcher.matches())
                .as("'%s' is not a valid JUnit timeout (expected <number>[ns|us|ms|s|m|h|d])", value)
                .isTrue();

        var amount = Long.parseLong(matcher.group(1));
        var unit = matcher.group(2) == null ? "s" : matcher.group(2).toLowerCase(Locale.ROOT);

        return switch (unit) {
            case "ns" -> Duration.ofNanos(amount).toSeconds();
            // multiplyExact, not *: a huge microsecond count would otherwise wrap negative here,
            // before the retry-budget check ever sees it.
            case "μs", "us" -> Duration.ofNanos(Math.multiplyExact(amount, 1_000L)).toSeconds();
            case "ms" -> Duration.ofMillis(amount).toSeconds();
            case "s" -> amount;
            case "m" -> Duration.ofMinutes(amount).toSeconds();
            case "h" -> Duration.ofHours(amount).toSeconds();
            case "d" -> Duration.ofDays(amount).toSeconds();
            default -> throw new IllegalStateException("unreachable: grammar admitted '" + unit + "'");
        };
    }
}
