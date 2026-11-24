package com.comet.opik.domain.topics;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.common.base.Throwables;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
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
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The topics read API: a project's topics over the window, the last {@code topics.build.windowDays} UTC days, today
 * included, in one of three states.
 * <ul>
 * <li>{@code disabled}: the workspace is not eligible for topics (not opted in to topics or to ai-labels); no topics
 * are read and ClickHouse is not called, also when the project still has topics from before an opt-out.</li>
 * <li>{@code collecting}: eligible, no saved list; the body says how many traces with a vector the window has and
 * how many the first build needs, and, as {@code attempt}, whether the project's current or last attempt is running or
 * failed.</li>
 * <li>{@code ready}: eligible with a saved list; {@link TopicsCounts} folds the counts of
 * {@link TraceTopicsDAO#countByTopicAndDay}, cached by {@link #dayCounts}.</li>
 * <li>{@code too_large}: a ClickHouse read of the collecting or ready state tripped a {@code topics.read} guard. A
 * retry fails the same way, so it is a state of the project, not an error, and it carries no {@code Retry-After}.</li>
 * </ul>
 * The reads run under {@code topics.read}: its guards, and its budget for the request's ClickHouse time. The client
 * timeout is the budget and two seconds, so only a hang reaches it, answered 503 without {@code Retry-After}.
 * MySQL is read first, in one transaction: the project's {@code topic_projects} row and its topics. Then ClickHouse. There is no lock: a run that saves in between makes some of the request's rows count
 * as Other.
 */
@Slf4j
@Singleton
public class TopicReadService {

    static final String DAY_COUNTS_CACHE = "topics_day_counts";
    static final String UNAVAILABLE_MESSAGE = "Topics are unavailable; try again later";
    private static final Duration CLIENT_TIMEOUT_MARGIN = Duration.ofSeconds(2);

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
     * The rows of a ready read, cached for {@code cacheManager.caches.topics_day_counts}. The key holds the project's
     * {@code list_run_id}, so a saved run starts a new entry, and the window's first instant, so the entry changes at
     * UTC midnight. The client timeout is inside: a cache that hangs is a late miss, and the ClickHouse read still gets
     * its whole time. An error is not cached. Not private: Guice intercepts the call from {@link #get}.
     */
    @Cacheable(name = DAY_COUNTS_CACHE, key = "$workspaceId + '-' + $projectId + '-' + $listRunId + '-' + $from", returnType = TopicDayCount.class, wrapperType = List.class)
    public Mono<List<TopicDayCount>> dayCounts(@NonNull String workspaceId, @NonNull UUID projectId, UUID listRunId,
            @NonNull Instant from, @NonNull Instant to) {
        return traceTopicsDAO.countByTopicAndDay(workspaceId, projectId, from, to)
                .collectList()
                .timeout(clientTimeout());
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
        var project = scope.get().project();
        var from = startOf(TopicsCounts.windowFrom(today, windowDays));
        var to = startOf(today.plusDays(1));
        if (live.isEmpty()) {
            var read = config.getRead();
            return blockUnlessTooLarge(workspaceId, projectId, windowDAO
                    .countByProject(workspaceId, Set.of(projectId), from, to, read,
                            read.getBudget().toSeconds())
                    .next()
                    .map(TopicWindowDAO.ProjectCount::traces)
                    .defaultIfEmpty(0L)
                    .timeout(clientTimeout()))
                    .map(vectors -> TopicsCounts.window(today, windowDays).status(ProjectTopics.Status.COLLECTING)
                            .lastRunAt(TopicsCounts.lastRunAt(project))
                            .attempt(TopicsCounts.attempt(project))
                            .vectorsInWindow(vectors)
                            .minVectors((long) config.getBuild().getMinVectors())
                            .build())
                    .orElseGet(() -> tooLarge(today, windowDays));
        }
        return blockUnlessTooLarge(workspaceId, projectId,
                dayCounts(workspaceId, projectId, project.listRunId(), from, to))
                .map(rows -> TopicsCounts.ready(today, windowDays, live, project, rows))
                .orElseGet(() -> tooLarge(today, windowDays));
    }

    private static ProjectTopics tooLarge(LocalDate today, int windowDays) {
        return TopicsCounts.window(today, windowDays).status(ProjectTopics.Status.TOO_LARGE).build();
    }

    private Duration clientTimeout() {
        return config.getRead().getBudget().toJavaDuration().plus(CLIENT_TIMEOUT_MARGIN);
    }

    /** Empty when the workspace is not eligible for topics. */
    private Optional<TopicReadScope> readScope(String workspaceId, UUID projectId) {
        if (!workspaces.isEnabled(workspaceId)) {
            return Optional.empty();
        }
        return Optional.of(template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(TopicDAO.class);
            var project = handle.attach(TopicProjectDAO.class).findProjects(workspaceId, List.of(projectId)).stream()
                    .findFirst().orElse(null);
            var live = project == null || project.listRunId() == null
                    ? List.<Topic>of()
                    : dao.findByProject(workspaceId, projectId);
            return TopicReadScope.builder().live(live).project(project).build();
        }));
    }

    /**
     * Empty on a guard trip ({@link TopicsReadErrors#isGuardTrip}). The client timeout is a 503 without
     * {@code Retry-After}; any other error stays as it is.
     */
    private <T> Optional<T> blockUnlessTooLarge(String workspaceId, UUID projectId, Mono<T> read) {
        try {
            return Optional.ofNullable(read.block());
        } catch (RuntimeException exception) {
            if (TopicsReadErrors.isGuardTrip(exception)) {
                log.warn("Topics read is too large, workspace_id '{}', project_id '{}'", workspaceId, projectId,
                        exception);
                return Optional.empty();
            }
            if (Throwables.getCausalChain(exception).stream().noneMatch(TimeoutException.class::isInstance)) {
                throw exception;
            }
            log.warn("Topics read timed out, workspace_id '{}', project_id '{}'", workspaceId, projectId, exception);
            throw new ServiceUnavailableException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ErrorMessage(List.of(UNAVAILABLE_MESSAGE)))
                    .build());
        }
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
