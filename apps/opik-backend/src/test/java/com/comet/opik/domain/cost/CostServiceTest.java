package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostServiceTest {

    @Test
    void calculateCostForVideoGenerationUsesDuration() {
        BigDecimal cost = CostService.calculateCost("sora-2", "openai",
                Map.of("video_duration_seconds", 4), null);

        assertThat(cost).isEqualByComparingTo("0.4");
    }
}
