package com.comet.opik.infrastructure.llm;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Gemini custom parameters: read under the names of Google's GenerateContentConfig")
class GeminiCustomParametersTest {

    @Test
    @DisplayName("Opik's thinking level stays out of what goes to Google")
    void thinkingLevelStaysOut() {
        var customParameters = Map.<String, Object>of("thinking", Map.of("level", "high"), "topK", 40);

        assertThat(GeminiCustomParameters.forGoogle(customParameters)).isEqualTo(Map.of("topK", 40));
        assertThat(GeminiCustomParameters.thinkingOnly(customParameters))
                .isEqualTo(Map.of("thinking", Map.of("level", "high")));
        assertThat(GeminiCustomParameters.parse(Map.of("thinking", Map.of("level", "high"))).toJson())
                .isEqualTo("{}");
    }

    static Stream<Arguments> wrongKeys() {
        return Stream.of(
                arguments(Map.of("thinkingConfig", Map.of("thinking_budget", 100)), "thinkingConfig.thinking_budget"),
                arguments(Map.of("safetySettings", List.of(Map.of("categry", "HARM_CATEGORY_HATE_SPEECH"))),
                        "safetySettings[0].categry"),
                arguments(Map.of("model", "gemini-2.5-pro"), "model"));
    }

    @ParameterizedTest
    @MethodSource("wrongKeys")
    @DisplayName("an unknown key is named by its full path")
    void unknownKeyIsNamedByItsPath(Map<String, Object> customParameters, String path) {
        assertThatThrownBy(() -> GeminiCustomParameters.parse(customParameters))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("custom_parameters.%s isn't a Gemini setting. Use the names of Google's "
                        + "GenerateContentConfig, like topK or responseMimeType", path);
    }

    @Test
    @DisplayName("a value of the wrong type is named by its full path")
    void wrongTypeIsNamedByItsPath() {
        assertThatThrownBy(
                () -> GeminiCustomParameters.parse(Map.of("thinkingConfig", Map.of("thinkingBudget", "a lot"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("custom_parameters.thinkingConfig.thinkingBudget has a value of the wrong type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"httpOptions", "routingConfig", "modelSelectionConfig", "systemInstruction",
            "shouldReturnHttpResponse"})
    @DisplayName("a key that would change where the call goes, the model, the messages or the reply is refused")
    void opikManagedKeyIsRefused(String key) {
        assertThatThrownBy(() -> GeminiCustomParameters.parse(Map.of(key, Map.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("custom_parameters.%s is set by Opik from the request and cannot be replaced", key);
    }
}
