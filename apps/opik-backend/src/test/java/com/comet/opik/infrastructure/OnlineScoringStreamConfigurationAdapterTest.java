package com.comet.opik.infrastructure;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OnlineScoringStreamConfigurationAdapter maxInFlightBytes resolution")
class OnlineScoringStreamConfigurationAdapterTest {

    private static final long GLOBAL_BYTES = 1_000;
    private static final long STREAM_BYTES = 5_000;

    @Test
    void usesPerStreamOverrideWhenPresent() {
        var config = configWith(OnlineScoringConfig.StreamConfiguration.builder()
                .scorer("llm_as_judge")
                .streamName("stream_scoring_llm_as_judge")
                .codec("java")
                .maxInFlightBytes(STREAM_BYTES)
                .build());

        var adapter = OnlineScoringStreamConfigurationAdapter.create(
                config, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(adapter.getMaxInFlightBytes()).isEqualTo(STREAM_BYTES);
    }

    @Test
    void fallsBackToGlobalWhenStreamOverrideAbsent() {
        var config = configWith(OnlineScoringConfig.StreamConfiguration.builder()
                .scorer("llm_as_judge")
                .streamName("stream_scoring_llm_as_judge")
                .codec("java")
                .maxInFlightBytes(null)
                .build());

        var adapter = OnlineScoringStreamConfigurationAdapter.create(
                config, AutomationRuleEvaluatorType.LLM_AS_JUDGE);

        assertThat(adapter.getMaxInFlightBytes()).isEqualTo(GLOBAL_BYTES);
    }

    private OnlineScoringConfig configWith(OnlineScoringConfig.StreamConfiguration streamConfig) {
        var config = new OnlineScoringConfig();
        config.setMaxInFlightBytes(GLOBAL_BYTES);
        config.setStreams(List.of(streamConfig));
        return config;
    }
}
