package com.comet.opik.api.resources.oauth;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Consent Request Validation Test")
class ConsentRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private ConsentRequest.ConsentRequestBuilder validRequest() {
        return ConsentRequest.builder()
                .clientId("test-client")
                .redirectUri("http://localhost:1234/cb")
                .codeChallenge("abc123challenge")
                .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                .resource("https://www.comet.com/opik/api/v1/mcp");
    }

    @Test
    @DisplayName("valid request passes validation")
    void validRequest_noViolations() {
        Set<ConstraintViolation<ConsentRequest>> violations = validator.validate(validRequest().build());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("blank code_challenge is rejected")
    void blankCodeChallenge_violation() {
        Set<ConstraintViolation<ConsentRequest>> violations = validator
                .validate(validRequest().codeChallenge("  ").build());

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("codeChallenge");
    }

    @Test
    @DisplayName("code_challenge_method other than S256 is rejected")
    void nonS256CodeChallengeMethod_violation() {
        Set<ConstraintViolation<ConsentRequest>> violations = validator
                .validate(validRequest().codeChallengeMethod("plain").build());

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("codeChallengeMethod");
    }
}
