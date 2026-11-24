package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TopicsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> validLists() {
        return Stream.of(
                arguments("wsA", Map.of("wsA", TopicsStage.UI)),
                arguments("wsA:vectors", Map.of("wsA", TopicsStage.VECTORS)),
                arguments("wsA:build", Map.of("wsA", TopicsStage.BUILD)),
                arguments("wsA:arrival", Map.of("wsA", TopicsStage.ARRIVAL)),
                arguments("wsA:nightly", Map.of("wsA", TopicsStage.NIGHTLY)),
                arguments("wsA:ui", Map.of("wsA", TopicsStage.UI)),
                arguments(" wsA , wsB : vectors ,, wsC:build, ", Map.of(
                        "wsA", TopicsStage.UI, "wsB", TopicsStage.VECTORS, "wsC", TopicsStage.BUILD)));
    }

    @ParameterizedTest(name = "''{0}''")
    @MethodSource("validLists")
    void parsesIdsAndStages(String enabledWorkspaces, Map<String, TopicsStage> expected) {
        var config = withList(enabledWorkspaces);

        assertThat(config.enabledWorkspaceStages()).isEqualTo(expected);
        assertThat(config.enabledWorkspaceIds()).isEqualTo(expected.keySet());
        assertThat(VALIDATOR.validateProperty(config, "enabledWorkspacesValid")).isEmpty();
    }

    @ParameterizedTest(name = "''{0}''")
    @NullSource
    @ValueSource(strings = {"", " ", " , ,"})
    void blankListHasNoWorkspace(String enabledWorkspaces) {
        var config = withList(enabledWorkspaces);

        assertThat(config.enabledWorkspaceStages()).isEmpty();
        assertThat(config.enabledWorkspaceIds()).isEmpty();
        assertThat(VALIDATOR.validateProperty(config, "enabledWorkspacesValid")).isEmpty();
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"wsA:unknown", "wsA:Build", "wsA:", "wsA:build:ui", ":build", "wsA,wsA",
            "wsA:vectors, wsA:build"})
    void unknownStageOrRepeatedIdFailsValidation(String enabledWorkspaces) {
        var config = withList(enabledWorkspaces);

        assertThatThrownBy(config::enabledWorkspaceStages).isInstanceOf(IllegalArgumentException.class);
        assertThat(VALIDATOR.validateProperty(config, "enabledWorkspacesValid")).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("topics.enabledWorkspaces", "vectors, build, arrival, nightly, ui"));
    }

    @Test
    void parseFollowsALaterValue() {
        var config = withList("wsA:build");
        assertThat(config.enabledWorkspaceStages()).isEqualTo(Map.of("wsA", TopicsStage.BUILD));

        config.setEnabledWorkspaces("wsB");

        assertThat(config.enabledWorkspaceStages()).isEqualTo(Map.of("wsB", TopicsStage.UI));
    }

    @Test
    void laterStageIncludesEarlierOnes() {
        assertThat(TopicsStage.BUILD.includes(TopicsStage.VECTORS)).isTrue();
        assertThat(TopicsStage.BUILD.includes(TopicsStage.BUILD)).isTrue();
        assertThat(TopicsStage.BUILD.includes(TopicsStage.ARRIVAL)).isFalse();
        assertThat(TopicsStage.ALL.includes(TopicsStage.UI)).isTrue();
    }

    private static TopicsConfig withList(String enabledWorkspaces) {
        var config = new TopicsConfig();
        config.setEnabledWorkspaces(enabledWorkspaces);
        return config;
    }
}
