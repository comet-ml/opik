package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, never flushing the log. Each read
 * waits {@code logReadDelay} first: ClickHouse flushes the log on every node on its own interval, so once that has
 * passed every entry of the query, initial and shard-side, is written. It reads up to {@code maxLogReadAttempts}
 * times while the initial entry is missing. The settings are in {@link FreeFormSqlPostRunCheckConfig}.
 */
@Singleton
class FreeFormSqlQueryLogReader {

    /**
     * Only the waits between reads run here, on one daemon thread of their own, so they never hold a thread of the
     * shared schedulers. The reads themselves complete on the ClickHouse client's threads.
     */
    private static final Scheduler WAITS = Schedulers.newSingle("free-form-sql-query-log-waits", true);

    private final FreeFormSqlQueryDAO dao;
    private final FreeFormSqlPostRunCheckConfig config;

    // Explicit, not Lombok-generated: the Docker build stage compiles without lombok.config, so its
    // copyableAnnotations don't carry @Config onto a generated constructor and Guice injects an empty config.
    @Inject
    FreeFormSqlQueryLogReader(@NonNull FreeFormSqlQueryDAO dao,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig config) {
        this.dao = dao;
        this.config = config;
    }

    /**
     * @return the entries of {@code queryId}; without the initial one as {@code user} if it never appeared, which the
     *     check then reports
     */
    CompletableFuture<List<FreeFormSqlQueryLogEntry>> entries(@NonNull String queryId, @NonNull String user) {
        return read(queryId, user, 1).toFuture();
    }

    private Mono<List<FreeFormSqlQueryLogEntry>> read(String queryId, String user, int attempt) {
        return Mono.delay(Duration.ofMillis(config.getLogReadDelay().toMilliseconds()), WAITS)
                .then(Mono.fromFuture(() -> dao.fetchQueryLog(queryId, user)))
                .flatMap(entries -> hasInitial(entries, user) || attempt >= config.getMaxLogReadAttempts()
                        ? Mono.just(entries)
                        : read(queryId, user, attempt + 1));
    }

    private static boolean hasInitial(List<FreeFormSqlQueryLogEntry> entries, String user) {
        return entries.stream().anyMatch(entry -> entry.initial() && entry.user().equals(user));
    }
}
