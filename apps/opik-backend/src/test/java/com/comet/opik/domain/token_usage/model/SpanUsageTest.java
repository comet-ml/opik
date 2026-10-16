package com.comet.opik.domain.token_usage.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpanUsageTest {

    @Test
    void constructionCopiesTheWarningsItIsGiven() {
        var warnings = new ArrayList<>(List.of(UsageWarning.noMatchingVariant("gemini", Set.of("characters"))));

        var spanUsage = new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, warnings);
        warnings.clear();

        assertThat(spanUsage.warnings()).hasSize(1).isUnmodifiable();
    }
}
