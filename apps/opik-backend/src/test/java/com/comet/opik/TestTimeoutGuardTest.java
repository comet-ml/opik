package com.comet.opik;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the shipped per-test timeout still fits CI's retry budget.
 * <p>
 * CI runs with {@code -Dsurefire.rerunFailingTestsCount=3}, and a timeout is an ordinary failure to
 * Surefire, so a deterministic hang is attempted 4 times. If 4x the bound exceeds the job wall, the
 * job is cancelled before Surefire can publish the named failure report -- losing exactly the
 * diagnostic the timeout was added to provide. A 5m bound was caught by review for that reason.
 * <p>
 * Deliberately narrow: it reads the shipped value and checks the arithmetic. It does not
 * re-implement JUnit's timeout grammar (JUnit validates that itself, throwing
 * {@code DateTimeParseException} at engine startup) and does not scrape the workflow or discovery
 * script for literal substrings, which would break on unrelated formatting changes there.
 */
@DisplayName("Test Timeout Guard")
class TestTimeoutGuardTest {

    private static final String TIMEOUT_PROPERTY = "junit.jupiter.execution.timeout.testable.method.default";

    /** The wall this file's value has to fit: INTEGRATION_TIMEOUT in discover-backend-tests.sh. */
    private static final Duration INTEGRATION_JOB_WALL = Duration.ofMinutes(20);

    /** CI runs -Dsurefire.rerunFailingTestsCount=3, and a timeout is an ordinary failure. */
    private static final int ATTEMPTS_PER_HANG = 4;

    @Test
    @DisplayName("the shipped default survives CI's retry budget")
    void shippedDefaultFitsRetryBudget() {
        // Read the file, never System.getProperty: CI always passes a -D override, so consulting it
        // would mean the shipped default -- the value local runs actually inherit -- went unchecked.
        var shipped = loadShippedTimeout();

        var budget = shipped.multipliedBy(ATTEMPTS_PER_HANG);

        assertThat(budget)
                .as("%d attempts at the shipped '%s' must fit inside the %s job wall",
                        ATTEMPTS_PER_HANG, shipped, INTEGRATION_JOB_WALL)
                .isPositive()
                .isLessThan(INTEGRATION_JOB_WALL);
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

    private static Duration loadShippedTimeout() {
        var value = loadProperties().getProperty(TIMEOUT_PROPERTY);
        assertThat(value).as("%s must be configured", TIMEOUT_PROPERTY).isNotBlank();

        // Only the units the shipped value is ever expected to use. Anything else is a deliberate
        // failure rather than a guess: silently coercing an unrecognised value to a small number is
        // how an oversized bound would slip past the assertion above.
        var trimmed = value.trim();
        assertThat(trimmed).as("shipped timeout should be expressed in whole minutes or seconds")
                .matches("[1-9]\\d* ?[ms]");

        var amount = Long.parseLong(trimmed.replaceAll("[^0-9]", ""));
        return trimmed.endsWith("m") ? Duration.ofMinutes(amount) : Duration.ofSeconds(amount);
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
}
