package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicsRunPlanner;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The job's lock durations. TopicsDiscoveryJobIntegrationTest runs its flow on real Redis, MySQL and ClickHouse. */
@DisplayName("topics discovery job")
class TopicsDiscoveryJobTest {

    @Test
    @DisplayName("a slot is one interval less a tenth of it, at most 1s")
    void slot() {
        assertThat(TopicsDiscoveryJob.slot(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(TopicsDiscoveryJob.slot(Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(4_500));
    }

    @Test
    @DisplayName("the leader lease is planBudget + countTimeout + leaseSlack")
    void leaderLease() {
        var config = TestConfigUtils.loadConfigTest().getTopics();
        var job = new TopicsDiscoveryJob(config, mock(TopicsRunPlanner.class), mock(LockService.class));

        // 2m + 60s + 1m in config-test.yml.
        assertThat(job.leaderLease()).isEqualTo(Duration.ofMinutes(4));
    }
}
