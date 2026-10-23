package com.comet.opik.domain;

import com.comet.opik.api.GuardrailsValidationRequest;
import com.comet.opik.api.GuardrailsValidationRequest.ValidationDescriptor;
import com.comet.opik.api.GuardrailsValidationResponse;
import com.comet.opik.api.GuardrailsValidationResponse.ValidationResult;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.GuardrailsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.inference.DecideQuestion;
import com.comet.opik.infrastructure.inference.DecideResponse;
import com.comet.opik.infrastructure.inference.OpikInferenceService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@ImplementedBy(GuardrailsValidationServiceImpl.class)
public interface GuardrailsValidationService {

    /** Runs the requested checks on the text in one decide call and returns a verdict per check. Writes nothing. */
    Mono<GuardrailsValidationResponse> validate(GuardrailsValidationRequest request);
}

/**
 * Turns each check into decision questions, sends them all in one decide call and applies the server-side
 * thresholds. The check path has no other engine: if the decision service cannot answer, every check reports an
 * error and passes (FR-7, fail open).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsValidationServiceImpl implements GuardrailsValidationService {

    /** The decision service rejects a longer state; longer texts are cut and every verdict says so. */
    static final int MAX_TEXT_LENGTH = 16_384;
    /** Questions per decide call, the decision service's limit. */
    static final int MAX_QUESTIONS = 32;
    static final String MODE_ALLOW = "allow";
    static final String MODE_RESTRICT = "restrict";
    /** FR-5: what a workspace whose organization is not enabled is told. */
    static final String NOT_ENABLED_MESSAGE = "Guardrails is available on the Enterprise plan";

    private final @NonNull OpikInferenceService opikInferenceService;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public Mono<GuardrailsValidationResponse> validate(@NonNull GuardrailsValidationRequest request) {
        featureFlags.checkGuardrailsEnabled();
        featureFlags.checkOpikInferenceEnabled();
        if (!requestContext.get().isGuardrailsEnabled()) {
            throw new ForbiddenException(NOT_ENABLED_MESSAGE);
        }
        var rules = parseRules(request.validations());
        var questions = new LinkedHashMap<String, DecideQuestion>();
        rules.forEach(rule -> questions.putAll(rule.questions()));
        if (questions.size() > MAX_QUESTIONS) {
            throw new BadRequestException("The checks need %d questions; at most %d fit in one call"
                    .formatted(questions.size(), MAX_QUESTIONS));
        }
        boolean truncated = request.text().length() > MAX_TEXT_LENGTH;
        var state = truncated ? request.text().substring(0, MAX_TEXT_LENGTH) : request.text();
        var thresholds = configuration.getGuardrails();
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return opikInferenceService.decide(workspaceId, state, questions)
                    .map(response -> toResponse(rules,
                            rule -> rule.evaluate(response.answers(), thresholds, truncated)))
                    .onErrorResume(error -> {
                        log.warn("Guardrails check failed open on workspaceId '{}': {}", workspaceId,
                                error.toString());
                        return Mono.just(toResponse(rules, rule -> rule.failOpen(error)));
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
            case TOPIC -> new TopicRule(index, descriptor, stringList(index, config, "topics"), mode(index, config));
            case PROMPT_INJECTION -> new InjectionRule(index, descriptor);
            default -> throw new BadRequestException("validations[%d].type '%s' is not supported by this endpoint"
                    .formatted(index, descriptor.type()));
        };
    }

    private static List<String> entities(int index, JsonNode config) {
        var entities = stringList(index, config, "entities");
        for (var entity : entities) {
            if (!GuardrailQuestions.PII_DESCRIPTIONS.containsKey(entity)) {
                throw new BadRequestException("validations[%d].config.entities contains the unsupported class '%s'"
                        .formatted(index, entity));
            }
        }
        return entities;
    }

    private static List<String> stringList(int index, JsonNode config, String field) {
        var node = config.path(field);
        var values = new ArrayList<String>();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && StringUtils.isNotBlank(item.asText())) {
                    values.add(item.asText().trim());
                }
            });
        }
        if (!node.isArray() || values.size() != node.size() || values.isEmpty()) {
            throw new BadRequestException("validations[%d].config.%s must be a non-empty list of strings"
                    .formatted(index, field));
        }
        return values;
    }

    private static String mode(int index, JsonNode config) {
        var mode = config.path("mode").asText(null);
        if (!MODE_ALLOW.equals(mode) && !MODE_RESTRICT.equals(mode)) {
            throw new BadRequestException("validations[%d].config.mode must be '%s' or '%s'"
                    .formatted(index, MODE_ALLOW, MODE_RESTRICT));
        }
        return mode;
    }

    private static DecideResponse.Answer answer(Map<String, DecideResponse.Answer> answers, String questionId) {
        return Optional.ofNullable(answers.get(questionId))
                .orElseThrow(() -> new IllegalStateException(
                        "opik-inference returned no answer for question '%s'".formatted(questionId)));
    }

    private static double yes(Map<String, DecideResponse.Answer> answers, String questionId) {
        return Optional.ofNullable(answer(answers, questionId).noul()).orElse(0.0);
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

        Map<String, DecideQuestion> questions();

        ValidationResult evaluate(Map<String, DecideResponse.Answer> answers, GuardrailsConfig thresholds,
                boolean truncated);

        default ValidationResult failOpen(Throwable error) {
            var message = Optional.ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName());
            return result(true, JsonUtils.createObjectNode().put("error", message), message);
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
        public Map<String, DecideQuestion> questions() {
            var questions = new LinkedHashMap<String, DecideQuestion>();
            entities.forEach(entity -> questions.put(questionId(entity), GuardrailQuestions.pii(entity)));
            return questions;
        }

        private String questionId(String entity) {
            return "%d.pii.%s".formatted(index, entity);
        }

        @Override
        public ValidationResult evaluate(Map<String, DecideResponse.Answer> answers, GuardrailsConfig thresholds,
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

    private record TopicRule(int index, ValidationDescriptor descriptor, List<String> topics, String mode)
            implements
                Rule {

        @Override
        public Map<String, DecideQuestion> questions() {
            return Map.of(questionId(), GuardrailQuestions.topic(topics));
        }

        private String questionId() {
            return "%d.topic".formatted(index);
        }

        @Override
        public ValidationResult evaluate(Map<String, DecideResponse.Answer> answers, GuardrailsConfig thresholds,
                boolean truncated) {
            var probabilities = Optional.ofNullable(answer(answers, questionId()).probabilities())
                    .orElse(Map.of());
            var scores = JsonUtils.createObjectNode();
            var matched = JsonUtils.createObjectNode();
            for (var topic : topics) {
                double score = probabilities.getOrDefault(topic, 0.0);
                scores.put(topic, score);
                if (score >= thresholds.getTopicThreshold()) {
                    matched.put(topic, score);
                }
            }
            boolean passed = MODE_RESTRICT.equals(mode) ? matched.isEmpty() : !matched.isEmpty();
            var details = details(truncated);
            details.set("matched_topics_scores", matched);
            details.set("scores", scores);
            return result(passed, details, null);
        }
    }

    private record InjectionRule(int index, ValidationDescriptor descriptor) implements Rule {

        @Override
        public Map<String, DecideQuestion> questions() {
            return Map.of(questionId(), GuardrailQuestions.injection());
        }

        private String questionId() {
            return "%d.injection".formatted(index);
        }

        @Override
        public ValidationResult evaluate(Map<String, DecideResponse.Answer> answers, GuardrailsConfig thresholds,
                boolean truncated) {
            double score = yes(answers, questionId());
            var details = details(truncated);
            details.put("injection_score", score);
            return result(score < thresholds.getPromptInjectionThreshold(), details, null);
        }
    }
}
