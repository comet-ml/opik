package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisException;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Caps how many widget and public analytics-queries free-form SQL queries one workspace runs at once, across every
 * backend pod, with a Redis semaphore. Ollie's chart authoring on the internal endpoint does not go through it: our
 * internal model runs those queries.
 * A query that finds every slot taken gets 429 with Retry-After. When Redis fails ({@link RedisException}) the query
 * runs without the cap (fail open): ClickHouse's per-account concurrency cap still applies. Any other error fails the
 * request, so a bug cannot silently switch the cap off.
 */
@Slf4j
@Singleton
public class FreeFormSqlConcurrencyLimiter {

    public static final String TOO_MANY_QUERIES_MESSAGE = "Too many free-form SQL queries are running in this workspace. Please retry shortly.";

    private static final AttributeKey<String> OUTCOME_KEY = stringKey("outcome");
    private static final Attributes ACQUIRED = Attributes.of(OUTCOME_KEY, "acquired");
    private static final Attributes REJECTED = Attributes.of(OUTCOME_KEY, "rejected");
    private static final Attributes LIMIT_UNAVAILABLE = Attributes.of(OUTCOME_KEY, "limit_unavailable");

    /** Headroom over the query and the enforced log reads, for the EXPLAINs, the log reads' own queries and names. */
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(60);

    private final LockService lockService;
    private final int maxConcurrentQueries;
    private final Duration lease;
    private final LongCounter outcomes;

    // Hand-written rather than Lombok's: image builds do not read lombok.config, so a generated constructor loses the
    // @Config qualifiers and the configs are injected as empty default instances.
    @Inject
    public FreeFormSqlConcurrencyLimiter(@NonNull LockService lockService,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts,
            @NonNull @Config("databaseAnalyticsReadOnlyFreeFormExtendedSql") DatabaseAnalyticsReadOnlyFreeFormSqlConfig extended,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig postRunCheck) {
        this.lockService = lockService;
        this.maxConcurrentQueries = customCharts.getMaxConcurrentQueriesPerWorkspace();
        this.lease = slotLease(extended, postRunCheck);
        this.outcomes = GlobalOpenTelemetry.get()
                .getMeter(FreeFormSqlQueryService.METRIC_NAMESPACE)
                .counterBuilder("opik.free_form_sql.concurrency_limit")
                .setDescription("Per-workspace free-form SQL slot outcomes: acquired, rejected or limit_unavailable")
                .build();
    }

    /**
     * How long a slot outlives a pod that died mid-query. Derived, not configured, so it always covers a live request:
     * the query up to the client's socket timeout, plus every enforced log read, plus {@link #LEASE_MARGIN}. In audit
     * mode the check runs after the response, outside the slot.
     */
    private Duration slotLease(DatabaseAnalyticsReadOnlyFreeFormSqlConfig extended,
            FreeFormSqlPostRunCheckConfig postRunCheck) {
        var logReads = postRunCheck.getMode() == FreeFormSqlPostRunCheckConfig.Mode.ENFORCE
                ? postRunCheck.getLogReadDelay().toJavaDuration().multipliedBy(postRunCheck.getMaxLogReadAttempts())
                : Duration.ZERO;
        return extended.getSocketTimeout().toJavaDuration().plus(logReads).plus(LEASE_MARGIN);
    }

    /** Runs {@code query} in one of the workspace's slots and frees the slot when it completes, either way. */
    public <T> CompletableFuture<T> run(@NonNull String workspaceId, @NonNull Supplier<CompletableFuture<T>> query) {
        var lock = slotLock(workspaceId, maxConcurrentQueries);
        return acquire(lock).thenCompose(permitId -> runAndRelease(lock, permitId, query));
    }

    /**
     * The workspace's semaphore. The limit is in the key: Redisson sets a semaphore's permits only when creating it,
     * so a changed limit takes effect at once on a new semaphore instead of after the old one expires.
     */
    public static LockService.Lock slotLock(@NonNull String workspaceId, int maxConcurrentQueries) {
        return new LockService.Lock(workspaceId, "free-form-sql-slots-" + maxConcurrentQueries);
    }

    private CompletableFuture<Optional<String>> acquire(LockService.Lock lock) {
        return lockService.tryAcquireSlot(lock, maxConcurrentQueries, lease)
                .doOnNext(permitId -> outcomes.add(1, ACQUIRED))
                .map(Optional::of)
                .switchIfEmpty(Mono.defer(() -> {
                    outcomes.add(1, REJECTED);
                    return Mono.error(tooManyQueries());
                }))
                .onErrorResume(RedisException.class, error -> {
                    log.warn("Free-form SQL slot unavailable, running without the workspace limit, lock '{}'",
                            lock.key(), error);
                    outcomes.add(1, LIMIT_UNAVAILABLE);
                    return Mono.just(Optional.empty());
                })
                .toFuture();
    }

    private <T> CompletableFuture<T> runAndRelease(LockService.Lock lock, Optional<String> permitId,
            Supplier<CompletableFuture<T>> query) {
        CompletableFuture<T> result;
        try {
            result = query.get();
        } catch (RuntimeException error) {
            result = CompletableFuture.failedFuture(error);
        }
        return permitId.isEmpty()
                ? result
                : result.whenComplete((value, error) -> release(lock, permitId.get()));
    }

    private void release(LockService.Lock lock, String permitId) {
        lockService.releaseSlot(lock, permitId)
                .subscribe(released -> {
                }, error -> log.warn("Failed to release free-form SQL slot, it frees when its lease ends, lock '{}'",
                        lock.key(), error));
    }

    private static ClientErrorException tooManyQueries() {
        return new ClientErrorException(Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, FreeFormSqlQueryService.RETRY_AFTER_SECONDS)
                .entity(new ErrorMessage(List.of(TOO_MANY_QUERIES_MESSAGE)))
                .build());
    }
}
