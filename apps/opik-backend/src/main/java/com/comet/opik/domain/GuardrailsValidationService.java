package com.comet.opik.domain;

import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.api.GuardrailsValidationResponse.ValidationResult;
import com.comet.opik.infrastructure.GuardrailsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.inference.Answer;
import com.comet.opik.infrastructure.inference.DecideRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.Question;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.Lists;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@ImplementedBy(GuardrailsValidationServiceImpl.class)
public interface GuardrailsValidationService {

    /**
     * Runs the requested checks on the text in one decide call, or two when they need more than 32 questions, and
     * returns a verdict per check. Writes nothing.
     */
    Mono<GuardrailsValidationResponse> validate(GuardrailsValidationRequest request);
}

/**
 * Turns each check into decision questions, sends them through the shared {@link InferenceClient} in as few decide
 * calls as fit, run in parallel, and applies the server-side thresholds. The check path has no other engine: if the
 * decision service cannot answer, every check reports an error and passes (FR-7, fail open).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsValidationServiceImpl implements GuardrailsValidationService {

    /** The questions a request may need: two decide calls' worth. */
    static final int MAX_QUESTIONS = 2 * DecideRequest.MAX_QUESTIONS;
    /** The longest deadline opik-inference allows a realtime decide call: a slow answer is still an answer. */
    static final Duration DECIDE_DEADLINE = Duration.ofSeconds(5);
    /** Room past the service's deadline for its answer to arrive, or for a quick retry of an overloaded call. */
    static final Duration CLIENT_MARGIN = Duration.ofSeconds(1);
    /** What a check that could not run reports. The cause is logged, not returned: it may name internal hosts. */
    static final String FAIL_OPEN_MESSAGE = "Guardrail check unavailable; it passed without running";

    private final @NonNull InferenceClient inferenceClient;
    private final @NonNull OpikConfiguration configuration;

    @Override
    public Mono<GuardrailsValidationResponse> validate(@NonNull GuardrailsValidationRequest request) {
        var rules = parseRules(request.validations());
        var questions = new LinkedHashMap<String, Question>();
        rules.forEach(rule -> questions.putAll(rule.questions()));
        if (questions.size() > MAX_QUESTIONS) {
            throw new BadRequestException("The checks need %d questions; at most %d fit in two calls"
                    .formatted(questions.size(), MAX_QUESTIONS));
        }
        var batches = Lists.partition(List.copyOf(questions.entrySet()), DecideRequest.MAX_QUESTIONS);
        var thresholds = configuration.getGuardrails();
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            var deadline = Instant.now().plus(DECIDE_DEADLINE).plus(CLIENT_MARGIN);
            return Flux.fromIterable(batches)
                    .flatMap(batch -> inferenceClient.decide(decideRequest(workspaceId, request.text(), batch),
                            deadline))
                    .flatMapIterable(response -> response.result().answers().entrySet())
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .map(answers -> toResponse(rules, rule -> rule.evaluate(answers, thresholds)))
                    .onErrorResume(error -> {
                        log.warn("Guardrails check failed open on workspaceId '{}'", workspaceId, error);
                        return Mono.just(toResponse(rules, Rule::failOpen));
                    });
        });
    }

    private static DecideRequest decideRequest(String workspaceId, String text,
            List<Map.Entry<String, Question>> batch) {
        var questions = new LinkedHashMap<String, Question>();
        batch.forEach(question -> questions.put(question.getKey(), question.getValue()));
        return DecideRequest.builder()
                .orgId(workspaceId)
                .state(TextNode.valueOf(text))
                .questions(questions)
                .lane(DecideRequest.REALTIME_LANE)
                .deadlineMs(DECIDE_DEADLINE.toMillis())
                .cache(true)
                .build();
    }

    private static GuardrailsValidationResponse toResponse(List<Rule> rules,
            Function<Rule, ValidationResult> verdict) {
        var results = rules.stream().map(verdict).toList();
        boolean passed = results.stream().allMatch(ValidationResult::validationPassed);
        return GuardrailsValidationResponse.builder().validationPassed(passed).validations(results).build();
    }

    private static List<Rule> parseRules(List<ValidationDescriptor> descriptors) {
        var rules = new ArrayList<Rule>(descriptors.size());
        for (int index = 0; index < descriptors.size(); index++) {
            rules.add(parseRule(index, descriptors.get(index)));
        }
        return rules;
    }

    private static Rule parseRule(int index, ValidationDescriptor descriptor) {
        var config = descriptor.config();
        if (config.has("threshold")) {
            throw new BadRequestException(
                    "validations[%d].config.threshold is not accepted: thresholds are set by the server"
                            .formatted(index));
        }
        return switch (descriptor.type()) {
            case PII -> new PiiRule(index, descriptor, GuardrailQuestions.piiEntities(path(index), config));
            case TOPIC -> {
                var topics = GuardrailQuestions.topicLists(path(index), config);
                yield new TopicRule(index, descriptor, topics.allowed(), topics.restricted());
            }
            case PROMPT_INJECTION -> new InjectionRule(index, descriptor);
            default -> throw new BadRequestException("validations[%d].type '%s' is not supported by this endpoint"
                    .formatted(index, descriptor.type()));
        };
    }

    private static String path(int index) {
        return "validations[%d].config".formatted(index);
    }

    private static Answer answer(Map<String, Answer> answers, String questionId) {
        return Optional.ofNullable(answers.get(questionId))
                .orElseThrow(() -> new IllegalStateException(
                        "opik-inference returned no answer for question '%s'".formatted(questionId)));
    }

    private static double yes(Map<String, Answer> answers, String questionId) {
        if (answer(answers, questionId) instanceof Answer.NoulAnswer noul) {
            return noul.noul();
        }
        throw new IllegalStateException(
                "opik-inference answered question '%s' with the wrong type".formatted(questionId));
    }

    private static Map<String, Double> probabilities(Map<String, Answer> answers, String questionId) {
        if (answer(answers, questionId) instanceof Answer.ChoiceAnswer choice) {
            return choice.probabilities();
        }
        throw new IllegalStateException(
                "opik-inference answered question '%s' with the wrong type".formatted(questionId));
    }

    private sealed interface Rule permits PiiRule, TopicRule, InjectionRule {

        ValidationDescriptor descriptor();

        Map<String, Question> questions();

        ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds);

        default ValidationResult failOpen() {
            return result(true, JsonUtils.createObjectNode().put("error", FAIL_OPEN_MESSAGE), FAIL_OPEN_MESSAGE);
        }

        default ValidationResult result(boolean passed, ObjectNode details, String error) {
            return ValidationResult.builder()
                    .validationPassed(passed)
                    .type(descriptor().type())
                    .validationConfig(descriptor().config())
                    .validationDetails(details)
                    .error(error)
                    .build();
        }
    }

    private record PiiRule(int index, ValidationDescriptor descriptor, List<String> entities) implements Rule {

        @Override
        public Map<String, Question> questions() {
            var questions = new LinkedHashMap<String, Question>();
            entities.forEach(entity -> questions.put(questionId(entity), GuardrailQuestions.pii(entity)));
            return questions;
        }

        private String questionId(String entity) {
            return "%d.pii.%s".formatted(index, entity);
        }

        @Override
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds) {
            var scores = JsonUtils.createObjectNode();
            var detected = JsonUtils.createObjectNode();
            for (var entity : entities) {
                double score = yes(answers, questionId(entity));
                scores.put(entity, score);
                if (score >= thresholds.getPiiThreshold()) {
                    detected.putArray(entity).addObject().put("score", score);
                }
            }
            var details = JsonUtils.createObjectNode();
            details.set("detected_entities", detected);
            details.set("scores", scores);
            return result(detected.isEmpty(), details, null);
        }
    }

    /**
     * Each list is its own choice question, the shape the evaluation measured: the text fails on any restricted
     * topic, and, when allowed topics are given, on matching none of them.
     */
    private record TopicRule(int index, ValidationDescriptor descriptor, List<String> allowed, List<String> restricted)
            implements
                Rule {

        @Override
        public Map<String, Question> questions() {
            var questions = new LinkedHashMap<String, Question>();
            if (!restricted.isEmpty()) {
                questions.put(questionId("restricted"), GuardrailQuestions.topic(restricted));
            }
            if (!allowed.isEmpty()) {
                questions.put(questionId("allowed"), GuardrailQuestions.topic(allowed));
            }
            return questions;
        }

        private String questionId(String list) {
            return "%d.topic.%s".formatted(index, list);
        }

        @Override
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds) {
            var scores = JsonUtils.createObjectNode();
            var details = JsonUtils.createObjectNode();
            boolean passed = true;
            if (!restricted.isEmpty()) {
                var matched = matched(answers, "restricted", restricted, thresholds, scores);
                details.set("matched_restricted_topics", matched);
                passed = matched.isEmpty();
            }
            if (!allowed.isEmpty()) {
                var matched = matched(answers, "allowed", allowed, thresholds, scores);
                details.set("matched_allowed_topics", matched);
                passed = passed && !matched.isEmpty();
            }
            details.set("scores", scores);
            return result(passed, details, null);
        }

        private ObjectNode matched(Map<String, Answer> answers, String list, List<String> topics,
                GuardrailsConfig thresholds, ObjectNode scores) {
            var probabilities = probabilities(answers, questionId(list));
            var matched = JsonUtils.createObjectNode();
            for (var topic : topics) {
                // A topic missing from the answer would otherwise read as unmatched: a silent pass on a restricted list.
                var score = Optional.ofNullable(probabilities.get(topic))
                        .orElseThrow(() -> new IllegalStateException(
                                "opik-inference returned no probability for an option of question '%s'"
                                        .formatted(questionId(list))));
                scores.put(topic, score);
                if (score >= thresholds.getTopicThreshold()) {
                    matched.put(topic, score);
                }
            }
            return matched;
        }
    }

    private record InjectionRule(int index, ValidationDescriptor descriptor) implements Rule {

        @Override
        public Map<String, Question> questions() {
            return Map.of(questionId(), GuardrailQuestions.injection());
        }

        private String questionId() {
            return "%d.injection".formatted(index);
        }

        @Override
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds) {
            double score = yes(answers, questionId());
            var details = JsonUtils.createObjectNode();
            details.put("injection_score", score);
            return result(score < thresholds.getPromptInjectionThreshold(), details, null);
        }
    }
}
