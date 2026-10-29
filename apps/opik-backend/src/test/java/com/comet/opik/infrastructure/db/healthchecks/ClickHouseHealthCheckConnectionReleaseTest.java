package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import io.dropwizard.util.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-client cover for the abandonment path of {@link AbstractClickHouseHealthCheck}. The unit tests drive it with a
 * mocked future, which shows the handler is registered but not that a response the client builds after the deadline
 * has passed actually hands its pooled connection back.
 *
 * <p>The v2 client pools ten connections by default, so more than ten probes abandoned while their queries are still
 * running drain the pool outright if each one leaks. That is the production failure behind OPIK-8576: a pod stuck
 * unready for five hours, every query waiting out the client's ten-second acquire timeout, against a ClickHouse that
 * was healthy throughout.
 *
 * <p>The healthy probe asserted before the abandoned ones is what makes the one asserted after meaningful — it rules
 * out a fixture that never worked in the first place.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ClickHouse health check connection release")
class ClickHouseHealthCheckConnectionReleaseTest {

    /** Above the v2 client's default {@code max_open_connections} of 10, so a leaked connection per probe drains it. */
    private static final int ABANDONED_PROBES = 12;

    /**
     * Long enough that every abandoned query is still running when the last probe starts, and at ClickHouse's ceiling
     * for {@code sleep()}.
     */
    private static final String SLOW_QUERY = "SELECT sleep(3)";

    private static final Duration ABANDONED_PROBE_TIMEOUT = Duration.milliseconds(50);
    private static final Duration LIVE_PROBE_TIMEOUT = Duration.seconds(5);

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false);

    private Client clickHouseClient;

    @BeforeAll
    void beforeAll() {
        Startables.deepStart(clickHouse).join();
        clickHouseClient = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouse, clickHouse.getDatabaseName())
                .buildClient();
    }

    @AfterAll
    void afterAll() {
        if (clickHouseClient != null) {
            clickHouseClient.close();
        }
        clickHouse.stop();
    }

    @Test
    @DisplayName("probes abandoned at the deadline release their pooled connections once the query lands")
    void abandonedProbesReleaseTheirPooledConnections() {
        var liveProbe = new ClickHouseHealthCheck(clickHouseClient, LIVE_PROBE_TIMEOUT);
        assertThat(liveProbe.execute().isHealthy()).as("the probe is green before anything is abandoned").isTrue();

        var abandonedProbe = new SlowProbeHealthCheck(clickHouseClient, ABANDONED_PROBE_TIMEOUT);
        for (int probe = 0; probe < ABANDONED_PROBES; probe++) {
            assertThat(abandonedProbe.execute().isHealthy())
                    .as("abandoned probe %d outlives its deadline", probe)
                    .isFalse();
        }

        // Connections come back as the abandoned queries land, so this passes shortly after the first one does. Without
        // the release it never passes: the pool is gone and every attempt waits out the acquire timeout instead.
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(liveProbe.execute().isHealthy()).isTrue());
    }

    /**
     * Probe whose query really does outlive the client deadline. It drops the inherited {@code max_execution_time} cap
     * deliberately: with the cap the server would abort the query and the client would see a failure, not the late
     * success this path exists to clean up.
     */
    private static final class SlowProbeHealthCheck extends AbstractClickHouseHealthCheck {

        private SlowProbeHealthCheck(Client clickHouseClient, Duration healthCheckTimeout) {
            super(clickHouseClient, healthCheckTimeout, "slow-probe");
        }

        @Override
        protected Result check() {
            return executeProbe(clickHouseClient.query(SLOW_QUERY, newQuerySettings()), response -> Result.healthy());
        }

        @Override
        protected QuerySettings newQuerySettings() {
            return new QuerySettings();
        }
    }
}
