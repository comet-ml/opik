package com.comet.opik.api.resources.v1.events;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static org.assertj.core.api.Assertions.assertThat;

class SupportedJudgeProviderTest {

    // A retired model loses its picker label, so a default judge without one is a model the provider no longer
    // serves: gemini-2.0-flash answered 404 "no longer available" and those test suites got no assertion scores.
    @ParameterizedTest
    @EnumSource(SupportedJudgeProvider.class)
    void eachDefaultJudgeIsAModelThePickerStillOffers(SupportedJudgeProvider judge) {
        assertThat(bundledRegistry().findModel(judge.model())).hasValueSatisfying(found -> {
            assertThat(found.provider()).isEqualTo(judge.provider());
            assertThat(found.model().label()).isNotBlank();
        });
    }
}
