package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.Cacheable;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The topics read API: a project's topics over the window, the last {@code topics.build.windowDays} UTC days, today
 * included, in one of three states.
 * <ul>
 * <li>{@code disabled}: the workspace is not eligible for topics (not opted in to topics or to ai-labels); no topics
 * are read and ClickHouse is not called, also when the project still has topics from before an opt-out.</li>
 * <li>{@code collecting}: eligible, no live topic; the body says how many traces with a vector the window has and how
 * many the first build needs, and, as {@code attempt}, whether the project's newest run is running or failed.</li>
 * <li>{@code ready}: eligible with live topics; {@link TopicsCounts} folds the counts of
 * {@link TraceTopicsDAO#countByTopicAndDay}, cached by {@link #dayCounts}.</li>
 * </ul>
 * MySQL is read first: the project's state and its live topics, from MySQL, then, when collecting, the project's
 * newest run. Then ClickHouse. There is no lock: a run that saves in between makes some of the request's rows count
 * as Other. A ClickHouse read that reads more rows or bytes than it may is a 400 with {@link #TOO_LARGE_MESSAGE}; one
 * that trips the time or memory limit, or the client timeout, is a 503 with {@code Retry-After}.
 */
@Slf4j
@Singleton
public class TopicReadService {

    static final long MAX_EXECUTION_SECONDS = 10;
    static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(MAX_EXECUTION_SECONDS + 1);
    static final String DAY_COUNTS_CACHE = "topics_day_counts";
    public static final String TOO_LARGE_MESSAGE = "This project is too large for topics";

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaces;
    private final ProjectService projectService;
    private final TopicWindowDAO windowDAO;
    private final TraceTopicsDAO traceTopicsDAO;
    private final TopicsMetrics metrics;
    private final Clock clock;

    @Inject
    public TopicReadService(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaces, @NonNull ProjectService projectService,
            @NonNull TopicWindowDAO windowDAO, @NonNull TraceTopicsDAO traceTopicsDAO,
            @NonNull TopicsMetrics metrics) {
        this(config, template, workspaces, projectService, windowDAO, traceTopicsDAO, metrics, Clock.systemUTC());
    }

    TopicReadService(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaces, @NonNull ProjectService projectService,
            @NonNull TopicWindowDAO windowDAO, @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicsMetrics metrics,
            @NonNull Clock clock) {
        this.config = config;
        this.template = template;
        this.workspaces = workspaces;
        this.projectService = projectService;
        this.windowDAO = windowDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Throws not found for a project of another workspace. */
    public ProjectTopics get(@NonNull String workspaceId, @NonNull UUID projectId) {
        projectService.get(projectId, workspaceId);
        long start = System.nanoTime();
        ProjectTopics.Status status = null;
        try {
            var body = read(workspaceId, projectId);
            status = body.status();
            return body;
        } finally {
            metrics.apiRequest(status, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * The rows of a ready read, cached for {@code cacheManager.caches.topics_day_counts}. The key holds the state's
     * {@code last_run_id}, so a saved run starts a new entry, and the window's first instant, so the entry changes at
     * UTC midnight. The client timeout is inside: a cache that hangs is a late miss, and the ClickHouse read still gets
     * its whole time. An error is not cached. Not private: Guice intercepts the call from {@link #get}.
     */
    @Cacheable(name = DAY_COUNTS_CACHE, key = "$workspaceId + '-' + $projectId + '-' + $lastRunId + '-' + $from", returnType = TopicDayCount.class, wrapperType = List.class)
    public Mono<List<TopicDayCount>> dayCounts(@NonNull String workspaceId, @NonNull UUID projectId, UUID lastRunId,
            @NonNull Instant from, @NonNull Instant to) {
        return traceTopicsDAO.countByTopicAndDay(workspaceId, projectId, from, to, MAX_EXECUTION_SECONDS)
                .collectList()
                .timeout(CLIENT_TIMEOUT);
    }

    /**
     * The scope of a trace query filtered by topic. Empty with no read when {@code filters} is null or has no
     * {@code topic_id}.
     * Also empty where the read API shows no topic: topics off, a workspace not eligible, a project with no live
     * topic. A caller with a topic filter and an empty scope matches no trace. Otherwise the live topic ids and the
     * query guards of {@code topics.filter}. Reads MySQL twice (eligibility, live topics) on a bounded elastic
     * thread, with the workspace id from the Reactor context.
     */
    public Mono<Optional<TopicFilterScope>> filterScope(@Nullable List<? extends Filter> filters,
            @NonNull UUID projectId) {
        if (filters == null || FilterQueryBuilder.hasField(filters, TraceField.TOPIC_ID).isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return Mono.deferContextual(ctx -> Mono.fromCallable(
                () -> readFilterScope(ctx.get(RequestContext.WORKSPACE_ID), projectId))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Optional<TopicFilterScope> readFilterScope(String workspaceId, UUID projectId) {
        if (!config.isEnabled() || !workspaces.isEligible(workspaceId)) {
            return Optional.empty();
        }
        var live = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findLive(workspaceId, projectId));
        if (live.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TopicFilterScope.builder()
                .liveIds(live.stream().map(topic -> topic.id().toString()).toList())
                .maxExecutionSeconds(config.getFilter().getMaxExecutionTime().toSeconds())
                .maxMemoryUsage(config.getFilter().getMaxMemoryUsage())
                .build());
    }

    private ProjectTopics read(String workspaceId, UUID projectId) {
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int windowDays = config.getBuild().getWindowDays();
        var scope = readScope(workspaceId, projectId);
        if (scope.isEmpty()) {
            return TopicsCounts.window(today, windowDays).status(ProjectTopics.Status.DISABLED).build();
        }

        var live = scope.get().live();
        var state = scope.get().state();
        var from = startOf(TopicsCounts.windowFrom(today, windowDays));
        var to = startOf(today.plusDays(1));
        if (live.isEmpty()) {
            var newest = template.inTransaction(READ_ONLY,
                    handle -> handle.attach(TopicRunDAO.class).findNewest(workspaceId, projectId));
            long vectors = blockWithLimits(workspaceId, projectId, windowDAO
                    .countByProject(workspaceId, Set.of(projectId), from, to, MAX_EXECUTION_SECONDS)
                    .next()
                    .map(TopicWindowDAO.ProjectCount::traces)
                    .defaultIfEmpty(0L)
                    .timeout(CLIENT_TIMEOUT));
            return TopicsCounts.window(today, windowDays).status(ProjectTopics.Status.COLLECTING)
                    .lastRunAt(TopicsCounts.lastRunAt(state))
                    .attempt(TopicsCounts.attempt(newest.orElse(null)))
                    .vectorsInWindow(vectors)
                    .minVectors((long) config.getBuild().getMinVectors())
                    .build();
        }
        var rows = blockWithLimits(workspaceId, projectId,
                dayCounts(workspaceId, projectId, state.lastRunId(), from, to));
        return TopicsCounts.ready(today, windowDays, live, state, rows);
    }

    /**
     * Empty when the workspace is not eligible for topics. Otherwise the project's state, then its live topics, from
     * MySQL; the prototypes are not read. With no saved run, {@code state} is null and {@code live} empty.
     */
    private Optional<TopicReadScope> readScope(String workspaceId, UUID projectId) {
        if (!workspaces.isEligible(workspaceId)) {
            return Optional.empty();
        }
        return Optional.of(template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(TopicDAO.class);
            var state = dao.findStates(workspaceId, List.of(projectId)).stream().findFirst().orElse(null);
            var live = state == null ? List.<Topic>of() : dao.findLive(workspaceId, projectId);
            return TopicReadScope.builder().live(live).state(state).build();
        }));
    }

    /**
     * Blocks on a ClickHouse read, which carries its own client timeout. A read over its rows or bytes limit
     * ({@link TopicsReadErrors#isTooLarge}) becomes a 400: a retry reads the same project and fails the same way. A
     * time or memory limit or the client timeout ({@link TopicsReadErrors#isLimit}) becomes a 503 with Retry-After.
     * The raw ClickHouse text stays in the logs.
     */
    private <T> T blockWithLimits(String workspaceId, UUID projectId, Mono<T> read) {
        try {
            return read.block();
        } catch (RuntimeException exception) {
            if (TopicsReadErrors.isTooLarge(exception)) {
                log.warn("Topics read is too large, workspace_id '{}', project_id '{}'", workspaceId, projectId,
                        exception);
                throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage(List.of(TOO_LARGE_MESSAGE)))
                        .build());
            }
            if (!TopicsReadErrors.isLimit(exception)) {
                throw exception;
            }
            log.warn("Topics read hit a ClickHouse limit, workspace_id '{}', project_id '{}'", workspaceId, projectId,
                    exception);
            throw TopicsReadErrors.busy();
        }
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
