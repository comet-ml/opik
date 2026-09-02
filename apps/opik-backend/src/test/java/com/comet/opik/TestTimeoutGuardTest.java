package com.comet.opik;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
 * These tests are deliberately fast and cannot hang: the blocking case is bounded preemptively.
 */
@DisplayName("Test Timeout Guard")
class TestTimeoutGuardTest {

    private static final String TIMEOUT_PROPERTY = "junit.jupiter.execution.timeout.testable.method.default";

    /** The tightest job wall the bound has to fit inside: UNIT_TIMEOUT in discover-backend-tests.sh. */
    private static final Duration UNIT_JOB_WALL = Duration.ofMinutes(10);

    /** CI runs -Dsurefire.rerunFailingTestsCount=3, and a timeout is an ordinary failure to Surefire. */
    private static final int ATTEMPTS_PER_HANG = 4;

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
    @DisplayName("the shipped default survives CI's retry budget inside the unit job wall")
    void shippedDefaultFitsRetryBudget() {
        var configured = configuredTimeout();

        // 4 attempts at the bound must still leave room for compilation and suite overhead,
        // otherwise CI cancels the job before Surefire can publish the named failure -- losing
        // exactly the diagnostic the timeout exists to provide.
        assertThat(toSeconds(configured) * ATTEMPTS_PER_HANG)
                .as("%d attempts at '%s' must fit inside the %s unit job wall",
                        ATTEMPTS_PER_HANG, configured, UNIT_JOB_WALL)
                .isLessThan(UNIT_JOB_WALL.toSeconds());
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

    private static String configuredTimeout() {
        var override = System.getProperty(TIMEOUT_PROPERTY);
        return override != null ? override : loadProperties().getProperty(TIMEOUT_PROPERTY);
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

    private static long toSeconds(String value) {
        assertThat(value).as("per-test timeout must be configured").isNotBlank();
        var trimmed = value.trim();
        var amount = Long.parseLong(trimmed.replaceAll("\\D", ""));
        return trimmed.endsWith("m") ? TimeUnit.MINUTES.toSeconds(amount) : amount;
    }
}
