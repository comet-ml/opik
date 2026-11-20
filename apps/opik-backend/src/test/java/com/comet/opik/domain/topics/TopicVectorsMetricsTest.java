package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topic vectors metrics")
class TopicVectorsMetricsTest {

    @Test
    @DisplayName("embed error codes become metric attributes from the known set only; anything else is unknown")
    void metricCode() {
        assertThat(TopicVectorsMetrics.metricCode("quota_exceeded")).isEqualTo("quota_exceeded");
        assertThat(TopicVectorsMetrics.metricCode("out_of_time")).isEqualTo("out_of_time");
        assertThat(TopicVectorsMetrics.metricCode("client_busy")).isEqualTo("client_busy");
        assertThat(TopicVectorsMetrics.metricCode("something_new")).isEqualTo("unknown");
    }
}
