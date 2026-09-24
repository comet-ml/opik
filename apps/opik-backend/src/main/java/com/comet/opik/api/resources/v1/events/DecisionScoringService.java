package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine.ParsedFeedbackScores;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsResponse;
import com.comet.opik.infrastructure.llm.openrouter.decisions.OpenRouterDecisionsClient;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Scores with decisions models (TypeSafe Jev via the OpenRouter Decisions API) instead of a chat judge.
 *
 * <p>The rule maps onto a Decisions request as follows: the rendered prompt is the {@code state} the model
 * reads, and every score is one yes/no ({@code noul}) question keyed by the score name, with the score
 * description as its instructions. All scores go in a single call. Each answer is the probability of yes; it
 * becomes {@code 1} at {@link #TRUE_THRESHOLD} or above and {@code 0} below, with the probability in the reason.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DecisionScoringService {

    /** OpenRouter's context length for Jev, covering state and questions together. */
    static final int MAX_CONTEXT_TOKENS = 32_000;
    static final double TRUE_THRESHOLD = 0.5;

    private static final String REASON_TEMPLATE = "Probability: %s";

    private final @NonNull OpenRouterDecisionsClient decisionsClient;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull @Config("onlineScoring") OnlineScoringConfig onlineScoringConfig;

    /**
     * Builds the request from the rendered rule messages: their text, in order, is the {@code state}.
     * Non-text content is dropped; rules on decisions models are text-only.
     */
    public DecisionsRequest buildRequest(@NonNull String model, @NonNull List<ChatMessage> renderedMessages,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var state = renderedMessages.stream()
                .map(DecisionScoringService::textOf)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n\n"));
        var questions = new LinkedHashMap<String, DecisionsQuestion>();
        schema.forEach(score -> questions.put(score.name(),
                DecisionsQuestion.noul(StringUtils.defaultIfBlank(score.description(), score.name()))));
        return DecisionsRequest.builder()
                .model(model)
                .state(state)
                .questions(questions)
                .build();
    }

    /** Rough token count of the request, with the same chars-per-token ratio the chat path uses. */
    public int estimateTokens(@NonNull DecisionsRequest request) {
        long chars = request.state().length() + request.questions().entrySet().stream()
                .mapToLong(entry -> entry.getKey().length() + entry.getValue().instructions().length())
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, chars / onlineScoringConfig.getAgenticToolsCharsPerToken());
    }

    public boolean exceedsContext(@NonNull DecisionsRequest request) {
        return estimateTokens(request) > MAX_CONTEXT_TOKENS;
    }

    /**
     * Calls the Decisions API with the workspace's OpenRouter key and records the call on {@code recorder}.
     * The key lookup hits the database, so it runs on {@link Schedulers#boundedElastic()}.
     */
    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request, @NonNull String workspaceId,
            @NonNull EvaluationRecorder recorder) {
        var call = Mono.fromCallable(() -> llmProviderFactory.getClientApiConfig(workspaceId, request.model()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(config -> decisionsClient.decide(request, config));
        return recorder.recordDecisionCall(request, call);
    }

    /**
     * Maps the answers to one score per schema entry. An answer that is missing, null or outside
     * {@code [0, 1]} is reported as unreadable instead of stored.
     */
    public static ParsedFeedbackScores toFeedbackScores(@NonNull DecisionsResponse response,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var answers = Objects.requireNonNullElse(response.answers(), Map.<String, DecisionsResponse.Answer>of());
        var scores = new ArrayList<FeedbackScoreBatchItem>();
        var unreadable = new ArrayList<String>();
        schema.forEach(score -> {
            var answer = answers.get(score.name());
            var probability = answer == null ? null : answer.noul();
            if (probability == null || probability.isNaN() || probability < 0 || probability > 1) {
                unreadable.add(score.name());
                return;
            }
            scores.add(FeedbackScoreBatchItem.builder()
                    .name(score.name())
                    .value(probability >= TRUE_THRESHOLD ? BigDecimal.ONE : BigDecimal.ZERO)
                    .reason(REASON_TEMPLATE.formatted(
                            BigDecimal.valueOf(probability).stripTrailingZeros().toPlainString()))
                    .source(ScoreSource.ONLINE_SCORING)
                    .build());
        });
        return ParsedFeedbackScores.builder()
                .scores(scores)
                .unreadableScoreNames(unreadable)
                .build();
    }

    private static String textOf(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> user.contents().stream()
                    .filter(TextContent.class::isInstance)
                    .map(content -> ((TextContent) content).text())
                    .collect(Collectors.joining("\n"));
            case SystemMessage system -> system.text();
            default -> null;
        };
    }
}
