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
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.hash.Hashing;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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

    /** The {@code outcome} of a call whose answer could not be read. */
    private static final String BAD_ANSWER = "bad_answer";

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull InferenceClient client;
    private final @NonNull TopicsMetrics metrics;

    /** What the call returned, read, with the call's stats. */
    record Answer<T>(T value, ObjectNode stats) {
    }

    /**
     * The answer, turned by {@code read} into what the algorithm needs; when it cannot, {@code read} throws an
     * {@code llm_bad_answer} {@link TopicsRunException}, and the call counts as {@code bad_answer}. A {@code read} that
     * returns null instead counts the call as {@code bad_answer} too, and the run goes on with a null value. A failed
     * call errors with its code.
     */
    <T> Mono<Answer<T>> generate(@NonNull TopicRun run, @NonNull LlmRequest call, @NonNull Function<JsonNode, T> read) {
        return Mono.defer(() -> {
            var tag = call.tag();
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
            return client.generate(request, Instant.now().plus(deadline(call.call())))
                    .onErrorMap(InferenceException.class, exception -> failed(run, call.call(), exception))
                    // Off the HTTP client's thread: the caller's next step is CPU work.
                    .publishOn(Schedulers.boundedElastic())
                    .map(response -> answer(run, call, response, read));
        });
    }

    private <T> Answer<T> answer(TopicRun run, LlmRequest call, GenerateResponse response, Function<JsonNode, T> read) {
        var tag = call.tag();
        var output = response.result().output();
        log.info(
                "Topics run '{}' answer, call '{}', project '{}', input tokens '{}', output tokens '{}', cache hit '{}'",
                run.runId(), tag, run.projectId(), response.usage().inputTokens(), response.usage().outputTokens(),
                response.cacheHit());
        var canonical = output == null ? "null" : InferenceJson.canonical(output);
        log.info("Topics run '{}' answer body, call '{}', project '{}', chars '{}', sha256 '{}'", run.runId(), tag,
                run.projectId(), canonical.length(), sha256(canonical));
        var stats = JsonUtils.createObjectNode();
        stats.put("llm_calls", 1);
        stats.put("llm_input_tokens", response.usage().inputTokens());
        stats.put("llm_output_tokens", response.usage().outputTokens());
        stats.put("llm_cache_hit", response.cacheHit());
        try {
            // Counted once the answer is read: an answer that cannot be read is bad_answer, not ok.
            var answer = read.apply(output);
            if (answer == null) {
                metrics.llm(run.workspace(), call.call(), BAD_ANSWER);
                log.warn("Topics run '{}' answer could not be read, call '{}', project '{}'", run.runId(), tag,
                        run.projectId());
                return new Answer<>(null, stats);
            }
            metrics.llm(run.workspace(), call.call(), "ok");
            return new Answer<>(answer, stats);
        } catch (TopicsRunException exception) {
            metrics.llm(run.workspace(), call.call(), BAD_ANSWER);
            throw exception;
        }
    }

    private TopicsRunException failed(TopicRun run, LlmCall call, InferenceException exception) {
        if (exception.isSchemaViolation()) {
            // The service checks the answer against the schema: a cut or off-schema answer comes back so.
            metrics.llm(run.workspace(), call, BAD_ANSWER);
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
            case AUTHOR -> config.getRun().getLlmDeadline().toJavaDuration();
            case NAMING -> config.getRun().getNamingDeadline().toJavaDuration();
            case VERIFY -> config.getRun().getVerifyDeadline().toJavaDuration();
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
