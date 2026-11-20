package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.GenerateLane;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.hash.Hashing;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Makes a run's LLM calls through opik-inference, with the deadline of their kind, and turns their failures into the
 * run's error codes. Prompts and answers hold users' trace text: the logs get their size and hash only.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsLlm {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull InferenceClient client;
    private final @NonNull TopicsMetrics metrics;

    /**
     * The answer, turned by {@code read} into what the algorithm needs; when it cannot, {@code read} throws an
     * {@code llm_bad_answer} {@link TopicsRunException}, and the call counts as {@code bad_answer}.
     */
    <T> Mono<T> generate(@NonNull TopicRun run, @NonNull LlmRequest call, @NonNull Function<JsonNode, T> read) {
        return Mono.defer(() -> {
            var tag = call.call().getValue();
            log.info("Topics run '{}' prompt, call '{}', project '{}', chars '{}', sha256 '{}'", run.runId(), tag,
                    run.projectId(), call.prompt().length(), sha256(call.prompt()));
            var request = GenerateRequest.builder()
                    .orgId(run.workspaceId())
                    .workspaceId(run.workspaceId())
                    .requestId(run.runId() + ":" + tag)
                    .cache(true)
                    .lane(GenerateLane.BATCH)
                    .messages(List.of(Message.builder().role(Message.Role.USER).content(call.prompt()).build()))
                    .schema(call.schema())
                    .maxTokens(call.maxTokens())
                    .temperature(0)
                    .build();
            run.add("llm_calls", 1);
            return client.generate(request, Instant.now().plus(deadline(call.call())))
                    .onErrorMap(InferenceException.class, exception -> failed(run, call.call(), exception))
                    .map(response -> answer(run, call.call(), response, read));
        });
    }

    private <T> T answer(TopicRun run, LlmCall call, GenerateResponse response, Function<JsonNode, T> read) {
        var tag = call.getValue();
        var output = response.result().output();
        log.info(
                "Topics run '{}' answer, call '{}', project '{}', input tokens '{}', output tokens '{}', cache hit '{}'",
                run.runId(), tag, run.projectId(), response.usage().inputTokens(), response.usage().outputTokens(),
                response.cacheHit());
        var canonical = output == null ? "null" : InferenceJson.canonical(output);
        log.info("Topics run '{}' answer body, call '{}', project '{}', chars '{}', sha256 '{}'", run.runId(), tag,
                run.projectId(), canonical.length(), sha256(canonical));
        run.add("llm_input_tokens", response.usage().inputTokens());
        run.add("llm_output_tokens", response.usage().outputTokens());
        run.stats().put("llm_cache_hit", response.cacheHit());
        try {
            // Counted once the answer is read: an answer that cannot be read is bad_answer, not ok.
            var answer = read.apply(output);
            metrics.llm(run.workspace(), call, "ok");
            return answer;
        } catch (TopicsRunException exception) {
            metrics.llm(run.workspace(), call, "bad_answer");
            throw exception;
        }
    }

    private TopicsRunException failed(TopicRun run, LlmCall call, InferenceException exception) {
        if (exception.isSchemaViolation()) {
            // The service checks the answer against the schema: a cut or off-schema answer comes back so.
            metrics.llm(run.workspace(), call, "bad_answer");
            return new TopicsRunException(RunError.LLM_BAD_ANSWER,
                    "the LLM answer breaks the schema, status '%s'".formatted(exception.getHttpStatus()), exception);
        }
        boolean timeout = isTimeout(exception);
        metrics.llm(run.workspace(), call, timeout ? "timeout" : "error");
        return new TopicsRunException(timeout ? RunError.LLM_TIMEOUT : RunError.LLM_ERROR,
                "the LLM call failed, code '%s', status '%s'".formatted(exception.getCode(),
                        exception.getHttpStatus()),
                exception);
    }

    private Duration deadline(LlmCall call) {
        return switch (call) {
            case AUTHOR -> config.getBuild().getLlmDeadline().toJavaDuration();
        };
    }

    private static boolean isTimeout(InferenceException exception) {
        // A read timeout surfaces as a transport error whose cause chain holds the timeout.
        return exception.isOutOfTime()
                || ExceptionUtils.indexOfType(exception, SocketTimeoutException.class) >= 0
                || ExceptionUtils.indexOfType(exception, TimeoutException.class) >= 0;
    }

    /** Lets a prompt or an answer be matched across logs (opik-inference's included) without logging its text. */
    private static String sha256(String text) {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
    }
}
