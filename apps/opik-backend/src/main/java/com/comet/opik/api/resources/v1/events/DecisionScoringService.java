package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine.ParsedFeedbackScores;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.ProviderFailures;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.llm.decisions.DecisionModels;
import com.comet.opik.infrastructure.llm.decisions.DecisionModels.DecisionModel;
import com.comet.opik.infrastructure.llm.decisions.DecisionsClient;
import com.comet.opik.infrastructure.llm.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.llm.decisions.DecisionsResponse;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.utils.RetryUtils;
import com.google.common.base.Preconditions;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

/**
 * Scores with decisions models instead of a chat judge, each through its provider's Decisions API: {@link DecisionModels}
 * says which rules use one and which provider serves the model, and the {@link DecisionsClient} registered for that
 * provider makes the call.
 *
 * <p>The rule maps onto a Decisions request as follows: the rendered prompt is the {@code state} the model
 * reads, and every score is one yes/no ({@code noul}) question with the score description as its instructions.
 * Questions are keyed by position ({@code q0}, {@code q1}, ...) rather than by score name: names are free text and
 * the API's key rules aren't documented, so a name it rejected would fail every evaluation of the rule with a 400.
 * All scores go in a single call. Each answer is the probability of yes; it
 * becomes {@code 1} at {@link #TRUE_THRESHOLD} or above and {@code 0} below, with the probability in the reason.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DecisionScoringService {

    static final double TRUE_THRESHOLD = 0.5;

    private static final String REASON_TEMPLATE = "Probability: %s";
    private static final String QUESTION_KEY_PREFIX = "q";
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    private static final int MAX_SUMMARY_CHARS = 1_000;

    private final @NonNull Set<DecisionsClient> decisionsClients;
    private final @NonNull LlmProviderFactory llmProviderFactory;

    /**
     * Builds the request from the rendered rule messages: their text, in order, is the {@code state}.
     * Non-text content is dropped; rules on decisions models are text-only. Only the
     * {@link #answerableScores answerable scores} become questions.
     */
    public DecisionsRequest buildRequest(@NonNull String model, @NonNull List<ChatMessage> renderedMessages,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var state = renderedMessages.stream()
                .map(DecisionScoringService::textOf)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n\n"));
        var scores = answerableScores(schema);
        var questions = new LinkedHashMap<String, DecisionsQuestion>();
        for (int i = 0; i < scores.size(); i++) {
            var score = scores.get(i);
            questions.put(questionKey(i),
                    DecisionsQuestion.noul(StringUtils.defaultIfBlank(score.description(), score.name())));
        }
        return DecisionsRequest.builder()
                .model(model)
                .state(state)
                .questions(questions)
                .build();
    }

    /**
     * Rough token count of the request. {@code charsPerToken} is the online-scoring ratio the chat path uses too;
     * the scorers pass it in, so this service needs no configuration of its own.
     */
    public static int estimateTokens(@NonNull DecisionsRequest request, int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got '%s'", charsPerToken);
        long chars = request.state().length() + request.questions().entrySet().stream()
                .mapToLong(entry -> entry.getKey().length() + entry.getValue().instructions().length())
                .sum();
        return (int) Math.min(Integer.MAX_VALUE, chars / charsPerToken);
    }

    /** The model's context length from {@link DecisionModels}; the smallest one for a model it doesn't list. */
    public static int maxContextTokens(@NonNull String model) {
        return DecisionModels.find(model)
                .map(DecisionModel::maxContextTokens)
                .orElse(DecisionModels.JEV_MAX_CONTEXT_TOKENS);
    }

    /** Whether an {@link #estimateTokens estimated} request is over the model's context length. */
    public static boolean exceedsContext(@NonNull String model, int estimatedTokens) {
        return estimatedTokens > maxContextTokens(model);
    }

    /**
     * Calls the model provider's Decisions API with the workspace's key for that provider and records the call on
     * {@code recorder}. The key lookup hits the database, so it runs on {@link Schedulers#boundedElastic()}.
     */
    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request, @NonNull String workspaceId,
            @NonNull EvaluationRecorder recorder) {
        var call = Mono.fromCallable(() -> llmProviderFactory.getClientApiConfig(workspaceId, request.model()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(config -> clientFor(request.model()).decide(request, config)
                        .onErrorMap(error -> !(error instanceof WebApplicationException
                                || error instanceof RetryUtils.RetryableHttpException),
                                DecisionScoringService::toProviderFailure));
        return recorder.recordDecisionCall(request, call);
    }

    /**
     * A failure the client did not map to a provider status, mapped as on the chat path: a refused request is the
     * caller's 400 with its fixed text, anything else a 500 with a redacted cause, as a transport failure's message
     * can name the destination and how it resolves.
     */
    private static WebApplicationException toProviderFailure(Throwable error) {
        var refused = ExceptionUtils.throwableOfType(error, ProviderRequestRefusedException.class);
        if (refused == null && DestinationGuardDns.isRefused(error)) {
            refused = new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.DESTINATION);
        }
        if (refused != null) {
            return new BadRequestException(refused.getMessage(), refused);
        }
        return new ServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, Response.Status.INTERNAL_SERVER_ERROR,
                ProviderFailures.redacted(error));
    }

    private DecisionsClient clientFor(String model) {
        var provider = DecisionModels.find(model)
                .map(DecisionModel::provider)
                .orElseThrow(() -> new IllegalStateException("Not a decisions model: '%s'".formatted(model)));
        return decisionsClients.stream()
                .filter(client -> client.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No Decisions API client for provider '%s'".formatted(provider)));
    }

    /**
     * Names of the scores a decisions model can't answer: yes/no questions only fit Boolean scores. Rule
     * validation rejects these, but test-suite assertions build their rule from the evaluator config and
     * never pass through it.
     */
    public static List<String> unsupportedScoreNames(@NonNull List<LlmAsJudgeOutputSchema> schema) {
        return schema.stream()
                .filter(score -> score.type() != LlmAsJudgeOutputSchemaType.BOOLEAN)
                .map(LlmAsJudgeOutputSchema::name)
                .toList();
    }

    /**
     * One-line rendering of the answers for the rule logs ({@code name=probability}), in score order. Score names
     * are user-defined, so control characters are replaced to keep a name from forging log lines.
     */
    public static String summarize(@NonNull DecisionsResponse response,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var answers = Objects.requireNonNullElse(response.answers(), Map.<String, DecisionsResponse.Answer>of());
        var scores = answerableScores(schema);
        var summary = IntStream.range(0, scores.size())
                .mapToObj(i -> "%s=%s".formatted(scores.get(i).name(), probabilityOf(answers.get(questionKey(i)))))
                .collect(Collectors.joining(", "));
        return StringUtils.abbreviate(CONTROL_CHARS.matcher(summary).replaceAll("?"), MAX_SUMMARY_CHARS);
    }

    /**
     * Maps the answers to one score per {@link #answerableScores answerable score}. An answer that is missing,
     * null or outside {@code [0, 1]} is reported as unreadable instead of stored.
     */
    public static ParsedFeedbackScores toFeedbackScores(@NonNull DecisionsResponse response,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        var answers = Objects.requireNonNullElse(response.answers(), Map.<String, DecisionsResponse.Answer>of());
        var answerable = answerableScores(schema);
        var scores = new ArrayList<FeedbackScoreBatchItem>();
        var unreadable = new ArrayList<String>();
        for (int i = 0; i < answerable.size(); i++) {
            var score = answerable.get(i);
            var probability = probabilityOf(answers.get(questionKey(i)));
            if (probability == null || probability.isNaN() || probability < 0 || probability > 1) {
                unreadable.add(score.name());
                continue;
            }
            scores.add(FeedbackScoreBatchItem.builder()
                    .name(score.name())
                    .value(probability >= TRUE_THRESHOLD ? BigDecimal.ONE : BigDecimal.ZERO)
                    .reason(REASON_TEMPLATE.formatted(
                            BigDecimal.valueOf(probability).stripTrailingZeros().toPlainString()))
                    .source(ScoreSource.ONLINE_SCORING)
                    .build());
        }
        return ParsedFeedbackScores.builder()
                .scores(scores)
                .unreadableScoreNames(unreadable)
                .build();
    }

    /**
     * The Boolean scores, first entry per name: the request map can only carry one question per name, so a
     * repeated name would otherwise be stored once per entry. Same first-wins rule as the chat judge's parser.
     */
    private static List<LlmAsJudgeOutputSchema> answerableScores(List<LlmAsJudgeOutputSchema> schema) {
        var seenNames = new HashSet<String>();
        return schema.stream()
                .filter(score -> score.type() == LlmAsJudgeOutputSchemaType.BOOLEAN)
                .filter(score -> seenNames.add(score.name()))
                .toList();
    }

    /** Key of the question asked for the {@code index}-th {@link #answerableScores answerable score}. */
    private static String questionKey(int index) {
        return QUESTION_KEY_PREFIX + index;
    }

    private static Double probabilityOf(DecisionsResponse.Answer answer) {
        return answer == null ? null : answer.noul();
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
