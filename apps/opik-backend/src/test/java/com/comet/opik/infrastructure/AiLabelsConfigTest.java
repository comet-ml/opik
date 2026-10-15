package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AiLabelsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String URL = "https://opik-inference.example.com";
    private static final String CREDENTIAL = "credential";

    static Stream<Arguments> inferenceSettings() {
        return Stream.of(
                arguments("disabled, nothing set", false, true, null, null, List.of()),
                arguments("enabled, url and credential set", true, true, URL, CREDENTIAL, List.of()),
                // The block itself is always required; config.yml always provides it.
                arguments("disabled, no inference block", false, false, null, null, List.of("inference")),
                arguments("enabled, no inference block", true, false, null, null,
                        List.of("inference", "inferenceConfigured")),
                arguments("enabled, blank url", true, true, " ", CREDENTIAL, List.of("inferenceConfigured")),
                arguments("enabled, no url", true, true, null, CREDENTIAL, List.of("inferenceConfigured")),
                arguments("enabled, blank credential", true, true, URL, "", List.of("inferenceConfigured")),
                arguments("enabled, no credential", true, true, URL, null, List.of("inferenceConfigured")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inferenceSettings")
    void inferenceIsRequiredOnlyWhenEnabled(String description, boolean enabled, boolean inferenceBlock, String url,
            String credential, List<String> expectedViolations) {
        var config = new AiLabelsConfig();
        config.setEnabled(enabled);
        if (inferenceBlock) {
            config.getInference().setUrl(url);
            config.getInference().setCredential(credential);
        } else {
            config.setInference(null);
        }

        var violations = VALIDATOR.validate(config).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
