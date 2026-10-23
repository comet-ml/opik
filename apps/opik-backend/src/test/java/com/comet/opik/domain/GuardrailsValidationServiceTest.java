package com.comet.opik.domain;

import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.inference.Answer;
import com.comet.opik.infrastructure.inference.DecideRequest;
import com.comet.opik.infrastructure.inference.DecideResponse;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.Question;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardrailsValidationServiceTest {

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String TEXT = "My email is a@b.c";

    private final InferenceClient inference = Mockito.mock(InferenceClient.class);
    private final WorkspaceFeaturesService workspaceFeatures = Mockito.mock(WorkspaceFeaturesService.class);
    private final RequestContext requestContext = RequestContext.builder().workspaceId(WORKSPACE_ID).build();
    private OpikConfiguration configuration;
    private GuardrailsValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        configuration = new OpikConfiguration();
        configuration.getServiceToggles().setGuardrailsValidationsEnabled(true);
        when(workspaceFeatures.isEnabled(requestContext, WorkspaceFeature.GUARDRAILS)).thenReturn(true);
        service = new GuardrailsValidationServiceImpl(inference, new FeatureFlags(configuration), configuration,
                () -> requestContext, workspaceFeatures);
    }

    @Test
    void workspaceWithoutEnablementIsForbiddenBeforeAnyCall() {
        when(workspaceFeatures.isEnabled(requestContext, WorkspaceFeature.GUARDRAILS)).thenReturn(false);

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
        when(inference.decide(any(), any())).thenReturn(Mono.just(answers(Map.of(
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

        var captor = ArgumentCaptor.forClass(DecideRequest.class);
        verify(inference).decide(captor.capture(), any());
        var sent = captor.getValue();
        assertThat(sent.orgId()).isEqualTo(WORKSPACE_ID);
        assertThat(sent.state().asText()).isEqualTo(TEXT);
        assertThat(sent.lane()).isEqualTo(DecideRequest.REALTIME_LANE);
        assertThat(sent.deadlineMs()).isEqualTo(configuration.getGuardrails().getDeadline().toMilliseconds());
        var questions = sent.questions();
        assertThat(questions.keySet()).containsExactly(
                "0.pii.EMAIL_ADDRESS", "0.pii.PHONE_NUMBER", "1.topic", "2.topic", "3.injection");
        assertThat(questions.get("0.pii.EMAIL_ADDRESS"))
                .isInstanceOfSatisfying(Question.NoulQuestion.class, question -> assertThat(
                        question.instructions().asText()).isEqualTo("Does this text contain an email address?"));
        assertThat(questions.get("1.topic"))
                .isInstanceOfSatisfying(Question.ChoiceQuestion.class, question -> assertThat(
                        question.criteria().keySet())
                        .containsExactly("billing", "shipping", GuardrailQuestions.NONE_OF_THESE));
        assertThat(questions.get("3.injection").instructions().asText())
                .isEqualTo(GuardrailQuestions.INJECTION_QUESTION);
    }

    @Test
    void restrictedTopicMatchAndMissingAllowedTopicBothFail() {
        var request = request(
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"legal advice\"],\"mode\":\"restrict\"}"),
                descriptor(GuardrailType.TOPIC, "{\"topics\":[\"billing\"],\"mode\":\"allow\"}"));
        when(inference.decide(any(), any())).thenReturn(Mono.just(answers(Map.of(
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
        when(inference.decide(any(), any()))
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
        when(inference.decide(any(), any())).thenReturn(Mono.error(
                new ServerErrorException("opik-inference decide request failed, status '503'", 503)));

        var response = validate(request);

        assertThat(response.validationPassed()).isTrue();
        assertThat(response.validations()).hasSize(2).allSatisfy(result -> {
            assertThat(result.validationPassed()).isTrue();
            assertThat(result.error()).isEqualTo(GuardrailsValidationServiceImpl.FAIL_OPEN_MESSAGE);
            assertThat(result.validationDetails().path("error").asText())
                    .isEqualTo(GuardrailsValidationServiceImpl.FAIL_OPEN_MESSAGE);
        });
    }

    @Test
    void topicsAtTheLimitsAreAccepted() {
        var topics = IntStream.range(0, GuardrailsValidationServiceImpl.MAX_TOPICS)
                .mapToObj(i -> i == 0 ? "t".repeat(GuardrailsValidationServiceImpl.MAX_TOPIC_LENGTH) : "topic " + i)
                .toList();
        var request = request(topicDescriptor(topics));
        when(inference.decide(any(), any())).thenReturn(Mono.error(new IllegalStateException("not answered")));

        validate(request);

        var captor = ArgumentCaptor.forClass(DecideRequest.class);
        verify(inference).decide(captor.capture(), any());
        assertThat(captor.getValue().questions().get("0.topic"))
                .isInstanceOfSatisfying(Question.ChoiceQuestion.class, question -> assertThat(question.criteria())
                        .hasSize(Question.ChoiceQuestion.MAX_OPTIONS));
    }

    static Stream<Arguments> rejectedTopicLists() {
        return Stream.of(
                Arguments.of("more topics than the choice question holds",
                        IntStream.rangeClosed(0, GuardrailsValidationServiceImpl.MAX_TOPICS)
                                .mapToObj(i -> "topic " + i)
                                .toList(),
                        "1 to " + GuardrailsValidationServiceImpl.MAX_TOPICS),
                Arguments.of("a topic over the length limit",
                        List.of("t".repeat(GuardrailsValidationServiceImpl.MAX_TOPIC_LENGTH + 1)),
                        "longer than " + GuardrailsValidationServiceImpl.MAX_TOPIC_LENGTH),
                Arguments.of("the reserved none-of-these option", List.of("billing", "None of these"),
                        GuardrailQuestions.NONE_OF_THESE),
                Arguments.of("a repeated topic", List.of("billing", "billing"), "more than once"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTopicLists")
    void topicListIsRejected(String scenario, List<String> topics, String expectedMessage) {
        assertThatThrownBy(() -> validate(request(topicDescriptor(topics))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(expectedMessage);
        verifyNoInteractions(inference);
    }

    @Test
    void piiEntitiesBeyondTheSupportedClassesOrRepeatedAreRejected() {
        assertThatThrownBy(() -> validate(request(descriptor(GuardrailType.PII,
                "{\"entities\":[\"EMAIL_ADDRESS\",\"EMAIL_ADDRESS\"]}"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("more than once");
        var tooMany = JsonUtils.createArrayNode();
        IntStream.rangeClosed(0, GuardrailQuestions.PII_DESCRIPTIONS.size()).forEach(i -> tooMany.add("EMAIL_ADDRESS"));
        assertThatThrownBy(() -> validate(request(ValidationDescriptor.builder()
                .type(GuardrailType.PII)
                .config(JsonUtils.createObjectNode().set("entities", tooMany))
                .build())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1 to " + GuardrailQuestions.PII_DESCRIPTIONS.size());
        verifyNoInteractions(inference);
    }

    @Test
    void disabledToggleIsForbiddenBeforeAnyCall() {
        var request = request(descriptor(GuardrailType.PROMPT_INJECTION, "{}"));

        configuration.getServiceToggles().setGuardrailsValidationsEnabled(false);
        assertThatThrownBy(() -> validate(request)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Guardrails");
        verifyNoInteractions(inference);
        verify(workspaceFeatures, never()).isEnabled(any(), any());
    }

    @Test
    void longTextIsCutToTheServiceLimitAndFlagged() {
        var longText = "x".repeat(GuardrailsValidationServiceImpl.MAX_TEXT_LENGTH + 100);
        var request = GuardrailsValidationRequest.builder()
                .text(longText)
                .validations(List.of(descriptor(GuardrailType.PROMPT_INJECTION, "{}")))
                .build();
        when(inference.decide(any(), any()))
                .thenReturn(Mono.just(answers(Map.of("0.injection", noul(0.01)))));

        var response = validate(request);

        assertThat(response.validations().get(0).validationDetails().path("truncated").asBoolean()).isTrue();
        var captor = ArgumentCaptor.forClass(DecideRequest.class);
        verify(inference).decide(captor.capture(), any());
        assertThat(captor.getValue().state().asText()).hasSize(GuardrailsValidationServiceImpl.MAX_TEXT_LENGTH);
    }

    private GuardrailsValidationResponse validate(GuardrailsValidationRequest request) {
        return service.validate(request)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();
    }

    private static GuardrailsValidationRequest request(ValidationDescriptor... descriptors) {
        return GuardrailsValidationRequest.builder().text(TEXT).validations(List.of(descriptors)).build();
    }

    private static ValidationDescriptor descriptor(GuardrailType type, String config) {
        return ValidationDescriptor.builder().type(type).config(JsonUtils.getJsonNodeFromString(config)).build();
    }

    private static ValidationDescriptor topicDescriptor(List<String> topics) {
        var config = JsonUtils.createObjectNode().put("mode", "restrict");
        topics.forEach(config.putArray("topics")::add);
        return ValidationDescriptor.builder().type(GuardrailType.TOPIC).config(config).build();
    }

    private static DecideResponse answers(Map<String, Answer> answers) {
        return DecideResponse.builder().requestId("req_1").result(new DecideResponse.Result(answers)).build();
    }

    private static Answer noul(double probability) {
        return new Answer.NoulAnswer(probability);
    }

    private static Answer choice(Map<String, Double> probabilities) {
        var best = probabilities.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        return new Answer.ChoiceAnswer(best.getKey(), probabilities, best.getValue());
    }
}
