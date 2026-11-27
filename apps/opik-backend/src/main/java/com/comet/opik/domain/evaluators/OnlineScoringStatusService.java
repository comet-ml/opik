package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.OnlineScoringStatus;
import com.comet.opik.api.evaluators.OnlineScoringStatus.Reason;
import com.comet.opik.api.evaluators.OnlineScoringStatus.State;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.llm.LlmProviderKeyNotConfiguredException;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Why each rule has not scored a trace yet, for the UI waiting on those scores. Kept in Redis for
 * {@link OnlineScoringConfig#getScoringStatusTtl()} after its latest write, after which the rule's logs remain the
 * record.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OnlineScoringStatusService {

    private static final String KEY_PREFIX = "online_scoring_status:";

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull @Config("onlineScoring") OnlineScoringConfig config;

    public Mono<Void> recordRetrying(@NonNull Trace trace, @NonNull String workspaceId, @NonNull UUID ruleId,
            boolean suiteAssertion, @NonNull Reason reason, int attempt, int maxAttempts) {
        return record(trace, workspaceId, ruleId, suiteAssertion, State.RETRYING, reason, attempt, maxAttempts);
    }

    public Mono<Void> recordFailed(@NonNull Trace trace, @NonNull String workspaceId, @NonNull UUID ruleId,
            boolean suiteAssertion, @NonNull Reason reason) {
        return record(trace, workspaceId, ruleId, suiteAssertion, State.FAILED, reason, 0, 0);
    }

    public Mono<Void> recordSkipped(@NonNull Trace trace, @NonNull String workspaceId, @NonNull UUID ruleId,
            boolean suiteAssertion, @NonNull Reason reason) {
        return record(trace, workspaceId, ruleId, suiteAssertion, State.SKIPPED, reason, 0, 0);
    }

    /** The rule stored every score it was due, so nothing is left to explain. */
    public Mono<Void> clear(@NonNull Trace trace, @NonNull String workspaceId, @NonNull UUID ruleId) {
        if (!isWatched(trace)) {
            return Mono.empty();
        }
        return bestEffort(statuses(workspaceId, trace.id()).fastRemove(ruleId.toString()), "clear", workspaceId,
                trace.id(), ruleId);
    }

    public Mono<List<OnlineScoringStatus>> find(@NonNull String workspaceId, @NonNull UUID traceId) {
        return statuses(workspaceId, traceId).readAllValues()
                .map(values -> values.stream()
                        .flatMap(json -> fromJson(json, workspaceId, traceId).stream())
                        .sorted(Comparator.comparing(OnlineScoringStatus::updatedAt))
                        .toList())
                .onErrorResume(error -> {
                    log.warn("Failed to read the online scoring statuses, workspace_id '{}', trace_id '{}'",
                            workspaceId, traceId, error);
                    return Mono.just(List.of());
                });
    }

    /** What a failed judge call says about the cause, without any of the text a provider answered. */
    public static Reason reasonOf(@NonNull Throwable error) {
        if (error instanceof LlmProviderKeyNotConfiguredException) {
            return Reason.NOT_CONFIGURED;
        }
        if (!(error instanceof WebApplicationException webError)) {
            return Reason.OTHER;
        }
        int status = webError.getResponse().getStatus();
        return switch (status) {
            case 401, 403 -> Reason.AUTHENTICATION;
            case 404 -> Reason.NOT_FOUND;
            case 408, 504 -> Reason.TIMEOUT;
            case 429 -> Reason.RATE_LIMITED;
            case 502 -> Reason.UNREACHABLE;
            default -> switch (Response.Status.Family.familyOf(status)) {
                case SERVER_ERROR -> Reason.SERVER_ERROR;
                case CLIENT_ERROR -> Reason.REJECTED;
                default -> Reason.OTHER;
            };
        };
    }

    /**
     * Only experiment runs are watched for their scores. Production traffic would write one key per trace for nothing.
     */
    private static boolean isWatched(Trace trace) {
        return trace.source() == Source.EXPERIMENT;
    }

    private Mono<Void> record(Trace trace, String workspaceId, UUID ruleId, boolean suiteAssertion, State state,
            Reason reason, int attempt, int maxAttempts) {
        if (!isWatched(trace)) {
            return Mono.empty();
        }
        var status = OnlineScoringStatus.builder()
                .ruleId(ruleId)
                .suiteAssertion(suiteAssertion)
                .state(state)
                .reason(reason)
                .attempt(attempt)
                .maxAttempts(maxAttempts)
                .updatedAt(Instant.now())
                .build();
        var statuses = statuses(workspaceId, trace.id());
        return bestEffort(Mono.defer(() -> statuses.fastPut(ruleId.toString(), JsonUtils.writeValueAsString(status)))
                .then(statuses.expire(config.getScoringStatusTtl().toJavaDuration())), "record", workspaceId,
                trace.id(), ruleId);
    }

    /**
     * A status only explains a missing score. Failing to write it must not fail the scoring, which would then be
     * retried.
     */
    private static Mono<Void> bestEffort(Mono<?> write, String action, String workspaceId, UUID traceId, UUID ruleId) {
        return write.then()
                .onErrorResume(error -> {
                    log.warn(
                            "Failed to write the online scoring status, action '{}', workspace_id '{}', trace_id '{}', rule_id '{}'",
                            action, workspaceId, traceId, ruleId, error);
                    return Mono.empty();
                });
    }

    private RMapReactive<String, String> statuses(String workspaceId, UUID traceId) {
        return redisClient.getMap(KEY_PREFIX + workspaceId + ":" + traceId, StringCodec.INSTANCE);
    }

    /**
     * An entry this version can't read, such as one written by a newer version during a deploy, hides only itself.
     */
    private static Optional<OnlineScoringStatus> fromJson(String json, String workspaceId, UUID traceId) {
        try {
            return Optional.of(JsonUtils.readValue(json, OnlineScoringStatus.class));
        } catch (UncheckedIOException exception) {
            log.warn("Skipping an unreadable online scoring status, workspace_id '{}', trace_id '{}'", workspaceId,
                    traceId, exception);
            return Optional.empty();
        }
    }
}
