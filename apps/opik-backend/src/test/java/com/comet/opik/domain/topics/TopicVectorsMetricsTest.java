package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topic vectors metrics")
class TopicVectorsMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    @DisplayName("the oldest-unvectored gauge reports each workspace's age, 0 with none, and nothing once the snapshot "
            + "is older than two tick intervals")
    void oldestUnvectored() {
        var vectors = new TopicsConfig.VectorsConfig();
        vectors.setTickInterval(io.dropwizard.util.Duration.minutes(1));
        var config = new TopicsConfig();
        config.setVectors(vectors);
        var meter = new TopicsTestMeter();
        var clock = new TopicsTestClock(NOW);
        var metrics = new TopicVectorsMetrics(config, TopicsTestMeter.names(), meter.meter, clock);

        metrics.oldest(Map.of("behind", NOW.minusSeconds(90), "caught-up", Instant.MAX));
        clock.advance(Duration.ofSeconds(30));

        assertThat(meter.observeGauge(TopicVectorsMetrics.OLDEST_UNVECTORED))
                .isEqualTo(Map.of("behind", 120.0, "caught-up", 0.0));

        clock.advance(Duration.ofMinutes(2));

        assertThat(meter.observeGauge(TopicVectorsMetrics.OLDEST_UNVECTORED)).isEmpty();
    }

    @Test
    @DisplayName("embed error codes become metric attributes from the known set only; anything else is unknown")
    void metricCode() {
        assertThat(TopicVectorsMetrics.metricCode("quota_exceeded")).isEqualTo("quota_exceeded");
        assertThat(TopicVectorsMetrics.metricCode("out_of_time")).isEqualTo("out_of_time");
        assertThat(TopicVectorsMetrics.metricCode("client_busy")).isEqualTo("client_busy");
        assertThat(TopicVectorsMetrics.metricCode("something_new")).isEqualTo("unknown");
    }
}
