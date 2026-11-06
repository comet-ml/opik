package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class GuardrailsInferenceConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String URL = "https://opik-inference.example.com";
    private static final String CREDENTIAL = "credential";

    static Stream<Arguments> inferenceSettings() {
        return Stream.of(
                arguments("guardrails off, nothing set", false, null, null, true),
                arguments("guardrails on, both set", true, URL, CREDENTIAL, true),
                arguments("guardrails on, no url", true, null, CREDENTIAL, false),
                arguments("guardrails on, blank url", true, " ", CREDENTIAL, false),
                arguments("guardrails on, no credential", true, URL, null, false),
                arguments("guardrails on, blank credential", true, URL, " ", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inferenceSettings")
    void guardrailsNeedTheInferenceUrlAndCredential(String description, boolean guardrailsEnabled, String url,
            String credential, boolean starts) {
        var config = new OpikConfiguration();
        config.getServiceToggles().setGuardrailsValidationsEnabled(guardrailsEnabled);
        config.getAiLabels().getInference().setUrl(url);
        config.getAiLabels().getInference().setCredential(credential);

        assertThat(VALIDATOR.validateProperty(config, "guardrailsInferenceConfigured")).hasSize(starts ? 0 : 1);
    }
}
