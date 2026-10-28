package com.comet.opik.domain;

import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.api.GuardrailsValidationResponse.ValidationResult;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.GuardrailsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.inference.Answer;
import com.comet.opik.infrastructure.inference.DecideRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.Question;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@ImplementedBy(GuardrailsValidationServiceImpl.class)
public interface GuardrailsValidationService {

    /** Runs the requested checks on the text in one decide call and returns a verdict per check. Writes nothing. */
    Mono<GuardrailsValidationResponse> validate(GuardrailsValidationRequest request);
}

/**
 * Turns each check into decision questions, sends them all in one decide call through the shared
 * {@link InferenceClient} and applies the server-side thresholds. The check path has no other engine: if the
 * decision service cannot answer, every check reports an error and passes (FR-7, fail open).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsValidationServiceImpl implements GuardrailsValidationService {

    /** The decision service rejects a longer state; longer texts are cut and every verdict says so. */
    static final int MAX_TEXT_LENGTH = 16_384;
    /** Each topic list is one choice question, which adds "none of these" to it, within the option limit. */
    static final int MAX_TOPICS = Question.ChoiceQuestion.MAX_OPTIONS - 1;
    /** A topic is a short label the model reads as one option of the topic question. */
    static final int MAX_TOPIC_LENGTH = 256;
    /** What a check that could not run reports. The cause is logged, not returned: it may name internal hosts. */
    static final String FAIL_OPEN_MESSAGE = "Guardrail check unavailable; it passed without running";
    static final String ALLOWED_TOPICS = "allowed_topics";
    static final String RESTRICTED_TOPICS = "restricted_topics";
    /** FR-5: what a workspace whose organization is not enabled is told. */
    static final String NOT_ENABLED_MESSAGE = "Guardrails is available on the Enterprise plan";

    private final @NonNull InferenceClient inferenceClient;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WorkspaceFeaturesService workspaceFeaturesService;

    @Override
    public Mono<GuardrailsValidationResponse> validate(@NonNull GuardrailsValidationRequest request) {
        featureFlags.checkGuardrailsValidationsEnabled();
        if (!workspaceFeaturesService.isEnabled(requestContext.get(), WorkspaceFeature.GUARDRAILS)) {
            throw new ForbiddenException(NOT_ENABLED_MESSAGE);
        }
        var rules = parseRules(request.validations());
        var questions = new LinkedHashMap<String, Question>();
        rules.forEach(rule -> questions.putAll(rule.questions()));
        if (questions.size() > DecideRequest.MAX_QUESTIONS) {
            throw new BadRequestException("The checks need %d questions; at most %d fit in one call"
                    .formatted(questions.size(), DecideRequest.MAX_QUESTIONS));
        }
        boolean truncated = request.text().length() > MAX_TEXT_LENGTH;
        var state = truncated ? request.text().substring(0, MAX_TEXT_LENGTH) : request.text();
        var thresholds = configuration.getGuardrails();
        var serviceDeadline = thresholds.getDeadline().toJavaDuration();
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            var decideRequest = DecideRequest.builder()
                    .orgId(workspaceId)
                    .state(TextNode.valueOf(state))
                    .questions(questions)
                    .lane(DecideRequest.REALTIME_LANE)
                    .deadlineMs(serviceDeadline.toMillis())
                    .cache(true)
                    .build();
            // Twice the service's deadline leaves the client room for one retry within its own.
            return inferenceClient.decide(decideRequest, Instant.now().plus(serviceDeadline.multipliedBy(2)))
                    .map(response -> toResponse(rules,
                            rule -> rule.evaluate(response.result().answers(), thresholds, truncated)))
                    .onErrorResume(error -> {
                        log.warn("Guardrails check failed open on workspaceId '{}'", workspaceId, error);
                        return Mono.just(toResponse(rules, Rule::failOpen));
                    });
        });
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
            case PII -> new PiiRule(index, descriptor, entities(index, config));
            case TOPIC -> topicRule(index, descriptor, config);
            case PROMPT_INJECTION -> new InjectionRule(index, descriptor);
            default -> throw new BadRequestException("validations[%d].type '%s' is not supported by this endpoint"
                    .formatted(index, descriptor.type()));
        };
    }

    private static List<String> entities(int index, JsonNode config) {
        var entities = stringList(index, config, "entities", GuardrailQuestions.PII_DESCRIPTIONS.size());
        for (var entity : entities) {
            if (!GuardrailQuestions.PII_DESCRIPTIONS.containsKey(entity)) {
                throw new BadRequestException("validations[%d].config.entities contains the unsupported class '%s'"
                        .formatted(index, entity));
            }
        }
        return entities;
    }

    private static TopicRule topicRule(int index, ValidationDescriptor descriptor, JsonNode config) {
        var allowed = topics(index, config, ALLOWED_TOPICS);
        var restricted = topics(index, config, RESTRICTED_TOPICS);
        if (allowed.isEmpty() && restricted.isEmpty()) {
            throw new BadRequestException("validations[%d].config needs %s or %s"
                    .formatted(index, ALLOWED_TOPICS, RESTRICTED_TOPICS));
        }
        // Topics are compared ignoring case, as the model reads them.
        var restrictedKeys = restricted.stream()
                .map(GuardrailsValidationServiceImpl::caseKey)
                .collect(Collectors.toSet());
        allowed.stream()
                .filter(topic -> restrictedKeys.contains(caseKey(topic)))
                .findFirst()
                .ifPresent(topic -> {
                    throw new BadRequestException("validations[%d].config has '%s' in both %s and %s"
                            .formatted(index, topic, ALLOWED_TOPICS, RESTRICTED_TOPICS));
                });
        return new TopicRule(index, descriptor, allowed, restricted);
    }

    /** An absent or empty list means the check does not use it. */
    private static List<String> topics(int index, JsonNode config, String field) {
        var node = config.path(field);
        if (node.isMissingNode() || node.isNull() || (node.isArray() && node.isEmpty())) {
            return List.of();
        }
        var topics = stringList(index, config, field, MAX_TOPICS);
        var topicKeys = new HashSet<String>();
        for (var topic : topics) {
            if (topic.length() > MAX_TOPIC_LENGTH) {
                throw new BadRequestException("validations[%d].config.%s has a topic longer than %d characters"
                        .formatted(index, field, MAX_TOPIC_LENGTH));
            }
            // The check adds this option itself; a policy topic with the same name would make its answer ambiguous.
            if (GuardrailQuestions.NONE_OF_THESE.equalsIgnoreCase(topic)) {
                throw new BadRequestException("validations[%d].config.%s cannot contain '%s'"
                        .formatted(index, field, GuardrailQuestions.NONE_OF_THESE));
            }
            // Case variants would be separate options of one choice question and split its probability.
            if (!topicKeys.add(caseKey(topic))) {
                throw new BadRequestException("validations[%d].config.%s contains '%s' more than once, ignoring case"
                        .formatted(index, field, topic));
            }
        }
        return topics;
    }

    /**
     * Equal exactly when equalsIgnoreCase matches the topics: it compares each character upper-cased, then
     * lower-cased, which a whole-string toLowerCase does not reproduce ('ı' and 'I', 'İ' and 'i').
     */
    private static String caseKey(String topic) {
        return topic.codePoints()
                .map(codePoint -> Character.toLowerCase(Character.toUpperCase(codePoint)))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static List<String> stringList(int index, JsonNode config, String field, int maxSize) {
        var node = config.path(field);
        if (!node.isArray() || node.isEmpty() || node.size() > maxSize) {
            throw new BadRequestException("validations[%d].config.%s must be a list of 1 to %d strings"
                    .formatted(index, field, maxSize));
        }
        var values = new LinkedHashSet<String>();
        for (var item : node) {
            if (!item.isTextual() || StringUtils.isBlank(item.asText())) {
                throw new BadRequestException("validations[%d].config.%s must contain only non-blank strings"
                        .formatted(index, field));
            }
            if (!values.add(item.asText().trim())) {
                throw new BadRequestException("validations[%d].config.%s contains a value more than once"
                        .formatted(index, field));
            }
        }
        return List.copyOf(values);
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

    private static ObjectNode details(boolean truncated) {
        var details = JsonUtils.createObjectNode();
        if (truncated) {
            details.put("truncated", true);
        }
        return details;
    }

    private sealed interface Rule permits PiiRule, TopicRule, InjectionRule {

        ValidationDescriptor descriptor();

        Map<String, Question> questions();

        ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds, boolean truncated);

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
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds,
                boolean truncated) {
            var scores = JsonUtils.createObjectNode();
            var detected = JsonUtils.createObjectNode();
            for (var entity : entities) {
                double score = yes(answers, questionId(entity));
                scores.put(entity, score);
                if (score >= thresholds.getPiiThreshold()) {
                    detected.putArray(entity).addObject().put("score", score);
                }
            }
            var details = details(truncated);
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
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds,
                boolean truncated) {
            var scores = JsonUtils.createObjectNode();
            var details = details(truncated);
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
                double score = probabilities.getOrDefault(topic, 0.0);
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
        public ValidationResult evaluate(Map<String, Answer> answers, GuardrailsConfig thresholds,
                boolean truncated) {
            double score = yes(answers, questionId());
            var details = details(truncated);
            details.put("injection_score", score);
            return result(score < thresholds.getPromptInjectionThreshold(), details, null);
        }
    }
}
