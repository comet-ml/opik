package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.cache.CacheManager;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(UsageReportServiceImpl.class)
interface UsageReportService {

    record UserCount(long allTimes, long daily) {
    }

    Optional<String> getAnonymousId();

    void saveAnonymousId(@NonNull String id);

    boolean isEventReported(@NonNull String eventType);

    void markEventAsReported(@NonNull String eventType);

    boolean shouldSendDailyReport();

    void markDailyReportAsSent();

    Mono<UserCount> getUserCount();

    boolean isFirstTraceReport();

}

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
class UsageReportServiceImpl implements UsageReportService {

    record Users(Set<String> allTimes, Set<String> daily) {
    }

    private final @NonNull TransactionTemplate template;
    private final @NonNull MetadataAnalyticsDAO metadataAnalyticsDAO;
    private final @NonNull CacheManager cacheManager;

    public Optional<String> getAnonymousId() {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(MetadataDAO.class).getMetadataKey(Metadata.ANONYMOUS_ID.getValue()));
    }

    public void saveAnonymousId(@NonNull String id) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(MetadataDAO.class).saveMetadataKey(Metadata.ANONYMOUS_ID.getValue(), id);
            return null;
        });
    }

    @Override
    public boolean isFirstTraceReport() {

        Boolean firstTraceReport = cacheManager.get(Metadata.FIRST_TRACE_CREATED.getValue(), Boolean.class).block();

        if (firstTraceReport == null) {
            firstTraceReport = template.inTransaction(READ_ONLY,
                    handle -> handle.attach(UsageReportDAO.class)
                            .isEventReported(Metadata.FIRST_TRACE_CREATED.getValue()));

            // Only store in cache if it was reported as we don't want to report again
            if (firstTraceReport) {
                cacheManager.put(Metadata.FIRST_TRACE_CREATED.getValue(), true, Duration.ofDays(1)).block();
            }

            return firstTraceReport;
        }

        return firstTraceReport;
    }

    public boolean isEventReported(@NonNull String eventType) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(UsageReportDAO.class).isEventReported(eventType));
    }

    public void markEventAsReported(@NonNull String eventType) {
        try {
            template.inTransaction(WRITE, handle -> {
                handle.attach(UsageReportDAO.class).markEventAsReported(eventType);
                return null;
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.warn("Event type already exists: {}", eventType);
            } else {
                log.error("Failed to add event", e);
            }
        }
    }

    public boolean shouldSendDailyReport() {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(MetadataDAO.class).shouldSendDailyReport());
    }

    @Override
    public void markDailyReportAsSent() {
        template.inTransaction(WRITE, handle -> {
            handle.attach(MetadataDAO.class).markDailyReportAsSent();
            return null;
        });
    }

    @Override
    public Mono<UserCount> getUserCount() {
        return Flux.fromIterable(List.of(getStateUsers(), getAnalyticsUsers()))
                .flatMap(Mono::from)
                .reduce((acc, curr) -> new Users(
                        reduceResults(acc.allTimes(), curr.allTimes()),
                        reduceResults(acc.daily(), curr.daily())))
                .map(users -> new UserCount(users.allTimes().size(), users.daily().size()));
    }

    private Mono<Users> getStateUsers() {
        return getStateTable()
                .flatMapMany(Flux::fromIterable)
                .flatMap(table -> Mono.zip(
                        getStateTableUsers(table, false),
                        getStateTableUsers(table, true)))
                .reduce((acc, curr) -> Tuples.of(
                        reduceResults(acc.getT1(), curr.getT1()),
                        reduceResults(acc.getT2(), curr.getT2())))
                .map(tuple -> new Users(tuple.getT1(), tuple.getT2()));
    }

    private Mono<Set<String>> getStateTableUsers(String table, boolean daily) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(MetadataDAO.class).getReportUsers(table, daily)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<List<String>> getStateTable() {
        return Mono
                .fromCallable(() -> template.inTransaction(READ_ONLY,
                        handle -> handle.attach(MetadataDAO.class)
                                .getTablesForDailyReport(handle.getConnection().getCatalog())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Set<String> reduceResults(Set<String> t1, Set<String> t2) {
        return Stream.concat(t1.stream(), t2.stream()).collect(Collectors.toSet());
    }

    private Mono<Users> getAnalyticsUsers() {
        return metadataAnalyticsDAO.getTablesForDailyReport()
                .flatMapMany(Flux::fromIterable)
                .flatMap(table -> Mono.zip(
                        metadataAnalyticsDAO.getAllTimesReportUsers(table),
                        metadataAnalyticsDAO.getDailyReportUsers(table)))
                .reduce((acc, curr) -> Tuples.of(
                        reduceResults(acc.getT1(), curr.getT1()),
                        reduceResults(acc.getT2(), curr.getT2())))
                .map(tuple -> new Users(tuple.getT1(), tuple.getT2()));
    }
}