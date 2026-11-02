package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.decisions.DecisionsClient;
import com.comet.opik.infrastructure.llm.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.llm.decisions.DecisionsResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.decisions.Decision;
import com.openai.models.decisions.DecisionCreateParams;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Objects;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

/**
 * Client for OpenAI's Decisions API ({@code POST {base URL}/decisions}), which serves gpt-6-luna to rules that ask
 * for it. Calls go through the official SDK client of the Responses API path, so they reach the workspace's OpenAI
 * base URL over the same guarded transport and with the same SDK retries.
 *
 * <p>It takes and returns the types of the OpenRouter Decisions client, so the scoring service treats both alike:
 * each question is asked as a {@code predicate} named by its key, and each {@code predicate} answer comes back with
 * its probability of yes in {@code noul}. A refusal has no probability and is left out, so its score is unreadable.
 *
 * <p>Errors carry the upstream status, as with the OpenRouter client: 4xx as {@link ClientErrorException} and 5xx as
 * {@link ServerErrorException}, so the online-scoring consumer drops permanent failures and redelivers transient ones.
 * The base URL is the workspace's, so the upstream message is kept only when it names an error OpenAI documents.
 */
@RequiredArgsConstructor
public class OpenAiDecisionsClient implements DecisionsClient {

    static final String PREDICATE_TYPE = "predicate";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final @NonNull OpenAIClientGenerator clientGenerator;

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPEN_AI;
    }

    @Override
    public Mono<DecisionsResponse> decide(@NonNull DecisionsRequest request,
            @NonNull LlmProviderClientApiConfig config) {
        return Mono.fromCallable(() -> clientGenerator.newResponsesApiClient(config, request.model(), null, false)
                .decisions()
                .create(toParams(request)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(OpenAiDecisionsClient::toResponse)
                .onErrorMap(OpenAIServiceException.class, OpenAiDecisionsClient::toWebApplicationException);
    }

    private static DecisionCreateParams toParams(DecisionsRequest request) {
        var params = DecisionCreateParams.builder()
                .model(request.model())
                .input(request.state());
        request.questions().forEach((name, question) -> params.addQuestion(
                DecisionCreateParams.Question.Predicate.builder()
                        .name(name)
                        .instructions(question.instructions())
                        .build()));
        return params.build();
    }

    private static DecisionsResponse toResponse(Decision decision) {
        var answers = new LinkedHashMap<String, DecisionsResponse.Answer>();
        for (var answer : decision.answers()) {
            if (answer.isPredicate()) {
                var predicate = answer.asPredicate();
                predicate.name().ifPresent(name -> answers.put(name, DecisionsResponse.Answer.builder()
                        .type(PREDICATE_TYPE)
                        .noul(predicate.probability())
                        .build()));
            }
        }
        var usage = decision.usage();
        return DecisionsResponse.builder()
                .model(decision.model())
                .answers(answers)
                .usage(DecisionsResponse.Usage.builder()
                        .inputTokens(Math.toIntExact(usage.inputTokens()))
                        .outputTokens(Math.toIntExact(usage.outputTokens()))
                        .cachedInputTokens(Math.toIntExact(usage.inputTokensDetails().cachedTokens()))
                        .build())
                .build();
    }

    private static WebApplicationException toWebApplicationException(OpenAIServiceException exception) {
        int status = exception.statusCode();
        var upstreamMessage = Objects.requireNonNullElse(
                LlmProviderOpenAiResponses.toErrorMessage(exception).getMessage(),
                UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        var message = "OpenAI Decisions API request failed, status '%s', message '%s'"
                .formatted(status, StringUtils.truncate(upstreamMessage, MAX_ERROR_MESSAGE_LENGTH));
        return switch (Response.Status.Family.familyOf(status)) {
            case CLIENT_ERROR -> new ClientErrorException(message, status);
            // ServerErrorException rejects any non-5xx status with an IllegalArgumentException.
            case SERVER_ERROR -> new ServerErrorException(message, status);
            default -> new WebApplicationException(message, status);
        };
    }
}
