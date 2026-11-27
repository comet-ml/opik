package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
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
                        "wsA", TopicsStage.UI, "wsB", TopicsStage.VECTORS, "wsC", TopicsStage.BUILD)),
                arguments("wsA,wsA", Map.of("wsA", TopicsStage.UI)),
                arguments("wsA, wsA:ui", Map.of("wsA", TopicsStage.UI)),
                arguments("wsA:build, wsB, wsA:build", Map.of("wsA", TopicsStage.BUILD, "wsB", TopicsStage.UI)));
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
    @ValueSource(strings = {"wsA:unknown", "wsA:Build", "wsA:", "wsA:build:ui", ":build"})
    void malformedEntryOrUnknownStageFailsValidation(String enabledWorkspaces) {
        var config = withList(enabledWorkspaces);

        assertThatThrownBy(config::enabledWorkspaceStages).isInstanceOf(IllegalArgumentException.class);
        assertThat(VALIDATOR.validateProperty(config, "enabledWorkspacesValid")).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("topics.enabledWorkspaces", "vectors, build, arrival, nightly, ui"));
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"wsA:vectors, wsA:build", "wsA, wsB, wsA:build"})
    void idListedWithTwoStagesFailsValidation(String enabledWorkspaces) {
        var config = withList(enabledWorkspaces);

        assertThatThrownBy(config::enabledWorkspaceStages).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace 'wsA'").hasMessageContaining("'build'");
        assertThat(VALIDATOR.validateProperty(config, "enabledWorkspacesValid")).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("topics.enabledWorkspaces", "one stage per id"));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("verifyExamples")
    @DisplayName("verify may show at most the examples naming and the group summaries picked")
    void verifyExamplesOrdered(String label, int verifyTopicExamples, int verifyGroupExamples, String message) {
        var algorithm = new TopicsConfig.AlgorithmConfig();
        algorithm.setNightlyTopicExamples(4);
        algorithm.setNightlyExamples(5);
        algorithm.setVerifyTopicExamples(verifyTopicExamples);
        algorithm.setVerifyGroupExamples(verifyGroupExamples);

        var messages = VALIDATOR.validate(algorithm).stream().map(violation -> violation.getMessage()).toList();

        assertThat(messages).filteredOn(text -> text.startsWith("topics.algorithm.verify"))
                .containsExactlyElementsOf(message == null ? List.of() : List.of(message));
    }

    static Stream<Arguments> verifyExamples() {
        return Stream.of(
                arguments("both equal to what was picked", 4, 5, null),
                arguments("more topic examples than naming picked", 5, 5,
                        "topics.algorithm.verifyTopicExamples must not be above nightlyTopicExamples"),
                arguments("more group examples than the summary picked", 4, 6,
                        "topics.algorithm.verifyGroupExamples must not be above nightlyExamples"));
    }

    private static TopicsConfig withList(String enabledWorkspaces) {
        var config = new TopicsConfig();
        config.setEnabledWorkspaces(enabledWorkspaces);
        return config;
    }
}
