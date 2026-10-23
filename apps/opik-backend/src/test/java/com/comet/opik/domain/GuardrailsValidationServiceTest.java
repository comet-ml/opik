package com.comet.opik.domain;

import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.inference.DecideQuestion;
import com.comet.opik.infrastructure.inference.DecideResponse;
import com.comet.opik.infrastructure.inference.OpikInferenceService;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardrailsValidationServiceTest {

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String TEXT = "My email is a@b.c";

    private final OpikInferenceService inference = Mockito.mock(OpikInferenceService.class);
    private final RequestContext requestContext = RequestContext.builder()
            .workspaceId(WORKSPACE_ID)
            .guardrailsEnabled(true)
            .build();
    private OpikConfiguration configuration;
    private GuardrailsValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        configuration = new OpikConfiguration();
        configuration.getServiceToggles().setGuardrailsEnabled(true);
        configuration.getServiceToggles().setOpikInferenceEnabled(true);
        service = new GuardrailsValidationServiceImpl(inference, new FeatureFlags(configuration), configuration,
                () -> requestContext);
    }

    @Test
    void workspaceWithoutEnablementIsForbiddenBeforeAnyCall() {
        requestContext.setGuardrailsEnabled(false);

        assertThatThrownBy(() -> validate(request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(GuardrailsValidationServiceImpl.NOT_ENABLED_MESSAGE);
        verifyNoInteractions(inference);
    }

    @Test
    void oneCallCarriesEveryQuestionAndAppliesTheThresholds() {
        var request = request(
                descriptor(GuardrailType.PII,
                        "{\"entities\":[\"EMAIL_ADDRESS\",\"PHONE_NUMBER\"],\"language\":\"en\"}"),
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"billing\",\"shipping\"],\"mode\":\"allow\"}"),
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"legal advice\"],\"mode\":\"restrict\"}"),
                descriptor(GuardrailType.PROMPT_INJECTION, "{}"));
        when(inference.decide(eq(WORKSPACE_ID), eq(TEXT), any())).thenReturn(Mono.just(answers(Map.of(
                "0.pii.EMAIL_ADDRESS", noul(0.97),
                "0.pii.PHONE_NUMBER", noul(0.02),
                "1.topic", choice(Map.of("billing", 0.65, "shipping", 0.05, "none of these", 0.3)),
                "2.topic", choice(Map.of("legal advice", 0.1, "none of these", 0.9)),
                "3.injection", noul(0.12)))));

        var response = validate(request);

        assertThat(response.validationPassed()).isFalse();
        assertThat(response.validations()).hasSize(4);

        var pii = response.validations().get(0);
        assertThat(pii.validationPassed()).isFalse();
        assertThat(pii.type()).isEqualTo(GuardrailType.PII);
        assertThat(pii.error()).isNull();
        assertThat(pii.validationConfig()).isEqualTo(request.validations().get(0).config());
        assertThat(pii.validationDetails().path("detected_entities").path("EMAIL_ADDRESS").get(0).path("score")
                .asDouble()).isEqualTo(0.97);
        assertThat(pii.validationDetails().path("detected_entities").has("PHONE_NUMBER")).isFalse();
        assertThat(pii.validationDetails().path("scores").path("PHONE_NUMBER").asDouble()).isEqualTo(0.02);

        var allowed = response.validations().get(1);
        assertThat(allowed.validationPassed()).isTrue();
        assertThat(allowed.validationDetails().path("matched_topics_scores").path("billing").asDouble())
                .isEqualTo(0.65);
        assertThat(allowed.validationDetails().path("matched_topics_scores").has("shipping")).isFalse();
        assertThat(allowed.validationDetails().path("scores").path("shipping").asDouble()).isEqualTo(0.05);

        var restricted = response.validations().get(2);
        assertThat(restricted.validationPassed()).isTrue();
        assertThat(restricted.validationDetails().path("matched_topics_scores").isEmpty()).isTrue();

        var injection = response.validations().get(3);
        assertThat(injection.validationPassed()).isTrue();
        assertThat(injection.validationDetails().path("injection_score").asDouble()).isEqualTo(0.12);

        var captor = questionsCaptor();
        verify(inference).decide(eq(WORKSPACE_ID), eq(TEXT), captor.capture());
        var questions = captor.getValue();
        assertThat(questions.keySet()).containsExactly(
                "0.pii.EMAIL_ADDRESS", "0.pii.PHONE_NUMBER", "1.topic", "2.topic", "3.injection");
        assertThat(questions.get("0.pii.EMAIL_ADDRESS").type()).isEqualTo(DecideQuestion.NOUL);
        assertThat(questions.get("0.pii.EMAIL_ADDRESS").instructions())
                .isEqualTo("Does this text contain an email address?");
        assertThat(questions.get("1.topic").type()).isEqualTo(DecideQuestion.CHOICE);
        assertThat(((Map<?, ?>) questions.get("1.topic").criteria()).keySet().stream().map(String::valueOf).toList())
                .containsExactly("billing", "shipping", GuardrailQuestions.NONE_OF_THESE);
        assertThat(questions.get("3.injection").instructions()).isEqualTo(GuardrailQuestions.INJECTION_QUESTION);
    }

    @Test
    void restrictedTopicMatchAndMissingAllowedTopicBothFail() {
        var request = request(
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"legal advice\"],\"mode\":\"restrict\"}"),
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"billing\"],\"mode\":\"allow\"}"));
        when(inference.decide(eq(WORKSPACE_ID), eq(TEXT), any())).thenReturn(Mono.just(answers(Map.of(
                "0.topic", choice(Map.of("legal advice", 0.4, "none of these", 0.6)),
                "1.topic", choice(Map.of("billing", 0.1, "none of these", 0.9))))));

        var response = validate(request);

        assertThat(response.validationPassed()).isFalse();
        assertThat(response.validations().get(0).validationPassed()).isFalse();
        assertThat(response.validations().get(1).validationPassed()).isFalse();
    }

    @Test
    void injectionAtTheThresholdFails() {
        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));
        when(inference.decide(eq(WORKSPACE_ID), eq(TEXT), any()))
                .thenReturn(Mono.just(answers(Map.of("0.injection", noul(0.4)))));

        var response = validate(request);

        assertThat(response.validationPassed()).isFalse();
        assertThat(response.validations().get(0).validationDetails().path("injection_score").asDouble())
                .isEqualTo(0.4);
    }

    @Test
    void thresholdInTheConfigIsRejected() {
        var request = request(descriptor(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"],\"threshold\":0.5}"));

        assertThatThrownBy(() -> validate(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("validations[0].config.threshold");
        verifyNoInteractions(inference);
    }

    @Test
    void unsupportedTypeAndUnknownPiiClassAreRejected() {
        assertThatThrownBy(() -> validate(request(descriptor(GuardrailType.LLM_JUDGE, "{}"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("LLM_JUDGE");
        assertThatThrownBy(() -> validate(request(descriptor(GuardrailType.PII, "{\"entities\":[\"US_SSN\"]}"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("US_SSN");
        assertThatThrownBy(() -> validate(request(descriptor(GuardrailType.TOPIC, "{\"topics\":[\"billing\"]}"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("mode");
        verifyNoInteractions(inference);
    }

    @Test
    void inferenceFailureFailsOpenWithTheErrorOnEveryEntry() {
        var request = request(
                descriptor(GuardrailType.PII, "{\"entities\":[\"EMAIL_ADDRESS\"]}"),
                descriptor(GuardrailType.PROMPT_INJECTION, "{}"));
        when(inference.decide(eq(WORKSPACE_ID), eq(TEXT), any())).thenReturn(Mono.error(
                new ServerErrorException("opik-inference decide request failed, status '503'", 503)));

        var response = validate(request);

        assertThat(response.validationPassed()).isTrue();
        assertThat(response.validations()).hasSize(2).allSatisfy(result -> {
            assertThat(result.validationPassed()).isTrue();
            assertThat(result.error()).contains("status '503'");
            assertThat(result.validationDetails().path("error").asText()).contains("status '503'");
        });
    }

    @Test
    void disabledTogglesAreForbiddenBeforeAnyCall() {
        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));

        configuration.getServiceToggles().setGuardrailsEnabled(false);
        assertThatThrownBy(() -> validate(request)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Guardrails");

        configuration.getServiceToggles().setGuardrailsEnabled(true);
        configuration.getServiceToggles().setOpikInferenceEnabled(false);
        assertThatThrownBy(() -> validate(request)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("inference");
        verifyNoInteractions(inference);
    }

    @Test
    void longTextIsCutToTheServiceLimitAndFlagged() {
        var longText = "x".repeat(GuardrailsValidationServiceImpl.MAX_TEXT_LENGTH + 100);
        var request = new GuardrailsValidationRequest(longText,
                List.of(descriptor(GuardrailType.PROMPT_INJECTION, "{}")));
        when(inference.decide(eq(WORKSPACE_ID), any(), any()))
                .thenReturn(Mono.just(answers(Map.of("0.injection", noul(0.01)))));

        var response = validate(request);

        assertThat(response.validations().get(0).validationDetails().path("truncated").asBoolean()).isTrue();
        var state = ArgumentCaptor.forClass(String.class);
        verify(inference).decide(eq(WORKSPACE_ID), state.capture(), any());
        assertThat(state.getValue()).hasSize(GuardrailsValidationServiceImpl.MAX_TEXT_LENGTH);
    }

    private GuardrailsValidationResponse validate(GuardrailsValidationRequest request) {
        return service.validate(request)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();
    }

    private static GuardrailsValidationRequest request(ValidationDescriptor... descriptors) {
        return new GuardrailsValidationRequest(TEXT, List.of(descriptors));
    }

    private static ValidationDescriptor descriptor(GuardrailType type, String config) {
        return new ValidationDescriptor(type, JsonUtils.getJsonNodeFromString(config));
    }

    private static DecideResponse answers(Map<String, DecideResponse.Answer> answers) {
        return DecideResponse.builder().requestId("req_1").result(new DecideResponse.Result(answers)).build();
    }

    private static DecideResponse.Answer noul(double probability) {
        return DecideResponse.Answer.builder().type(DecideQuestion.NOUL).noul(probability).build();
    }

    private static DecideResponse.Answer choice(Map<String, Double> probabilities) {
        return DecideResponse.Answer.builder().type(DecideQuestion.CHOICE).probabilities(probabilities).build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, DecideQuestion>> questionsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
