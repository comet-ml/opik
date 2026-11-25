package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The projects a discovery run is due for. One MySQL read finds the projects of the opted-in workspaces with no list,
 * no run in flight and a trace written in the window ({@link TopicProjectDAO#findListless}); the retry policy and the
 * backfill gate ({@link TopicsBackfillGate}) filter them; then one ClickHouse count per workspace keeps those with at
 * least {@code topics.run.minVectors} traces with a vector in the window.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsDiscoveryCandidates {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TopicsBackfillGate backfillGate;

    @Builder(toBuilder = true)
    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, long vectors) {
    }

    /**
     * The candidates for the window that ends at {@code night}'s start. The workspaces are counted in random order
     * until {@code topics.runs.planBudget}, so a slow count never starves the same workspaces. The result takes one
     * project of each workspace in turn, the most vectors first within a workspace, so one large workspace does not
     * take every free slot.
     */
    Mono<List<Candidate>> find(@NonNull Collection<String> workspaceIds, @NonNull LocalDate night,
            @NonNull Instant now) {
        if (workspaceIds.isEmpty()) {
            return Mono.just(List.of());
        }
        var from = windowFrom(night);
        var to = windowTo(night);
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findListless(workspaceIds, from)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(listless -> open(listless, now))
                .flatMapMany(open -> {
                    var workspaces = new ArrayList<>(open.keySet());
                    Collections.shuffle(workspaces);
                    return Flux.fromIterable(workspaces)
                            .concatMap(workspaceId -> count(workspaceId, open.get(workspaceId), from, to));
                })
                .take(config.getRuns().getPlanBudget().toJavaDuration())
                .collectList()
                .map(TopicsDiscoveryCandidates::roundRobin);
    }

    /** The project's traces with a vector in the window that ends at {@code night}'s start; 0 for none. */
    Mono<Long> windowVectors(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night) {
        return windowDAO.countByProject(workspaceId, Set.of(projectId), windowFrom(night), windowTo(night),
                countSeconds())
                .filter(count -> count.projectId().equals(projectId))
                .map(TopicWindowDAO.ProjectCount::traces)
                .next()
                .defaultIfEmpty(0L);
    }

    /** The project's row as the planner reads it, empty when a discovery run cannot be queued for it. */
    Mono<ListlessProject> findListless(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findListless(List.of(workspaceId), windowFrom(night))))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(rows -> rows)
                .filter(row -> row.projectId().equals(projectId))
                .next();
    }

    static Duration retryDelay(@NonNull List<Duration> delays, int failures) {
        return delays.get(Math.min(failures, delays.size()) - 1);
    }

    /** Whether the project's last failed discovery run, if any, is older than its retry delay of {@code delays}. */
    static boolean retryDue(@NonNull List<Duration> delays, @NonNull ListlessProject project, @NonNull Instant now) {
        if (project.failedRuns() == 0 || project.runFinishedAt() == null) {
            return true;
        }
        return !project.runFinishedAt().plus(retryDelay(delays, project.failedRuns())).isAfter(now);
    }

    /** A listless project whose retry is due, with its backfill gate's state. */
    private record Gated(ListlessProject project, TopicsBackfillGate.State state) {
    }

    /** Of the listless projects, by workspace, those whose retry is due and whose gate is open. */
    private Map<String, List<UUID>> open(List<ListlessProject> listless, Instant now) {
        var gated = listless.stream()
                .filter(project -> retryDue(config.getDiscovery().retryDelays(), project, now))
                .map(project -> new Gated(project, backfillGate.discoveryGate(project, now)))
                .toList();
        logOverdue(gated, now);
        return gated.stream()
                .filter(gate -> gate.state().isOpen())
                .collect(Collectors.groupingBy(gate -> gate.project().workspaceId(), LinkedHashMap::new,
                        Collectors.mapping(gate -> gate.project().projectId(), Collectors.toList())));
    }

    /**
     * Logs once per workspace the projects whose gate timed out closed: a stale workspace, or a backfill past
     * {@code backfillMaxWait} with too few vectors.
     */
    private void logOverdue(List<Gated> gated, Instant now) {
        gated.stream()
                .filter(gate -> gate.state() == TopicsBackfillGate.State.TIMED_OUT_WAITING)
                .collect(Collectors.groupingBy(gate -> gate.project().workspaceId(), LinkedHashMap::new,
                        Collectors.mapping(Gated::project, Collectors.toList())))
                .forEach((workspaceId, projects) -> log.warn(
                        "Topics discovery runs held back: the vectors tick stopped checking the workspace, or a "
                                + "backfill ran past topics.discovery.backfillMaxWait with too few traces embedded; "
                                + "backfillMaxWait '{}', workspace '{}', projects '{}', longest wait seconds '{}'",
                        config.getDiscovery().getBackfillMaxWait(), workspaceId, projects.size(),
                        projects.stream().map(project -> TopicsBackfillGate.waited(project, now))
                                .max(Comparator.naturalOrder()).orElse(Duration.ZERO).toSeconds()));
    }

    /** The workspace's projects with enough vectors, the most first. */
    private Mono<List<Candidate>> count(String workspaceId, List<UUID> projectIds, Instant from, Instant to) {
        return counts(workspaceId, projectIds, from, to)
                .map(counts -> counts.entrySet().stream()
                        .filter(entry -> entry.getValue() >= config.getRun().getMinVectors())
                        .map(entry -> Candidate.builder().workspaceId(workspaceId).projectId(entry.getKey())
                                .vectors(entry.getValue()).build())
                        .sorted(Comparator.comparingLong(Candidate::vectors).reversed()
                                .thenComparing(Candidate::projectId))
                        .toList());
    }

    /**
     * The window vectors of the workspace's projects, 0 for none, for the window that ends at {@code night}'s start.
     * One count for the workspace; when it fails, one per project. A project whose own count fails is left out.
     */
    Mono<Map<UUID, Long>> windowVectors(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds,
            @NonNull LocalDate night) {
        return counts(workspaceId, List.copyOf(projectIds), windowFrom(night), windowTo(night));
    }

    /**
     * One count for the workspace; when it fails (a large workspace can trip {@code max_rows_to_read}), one per
     * project, so a large project fails only its own and is left out.
     */
    private Mono<Map<UUID, Long>> counts(String workspaceId, List<UUID> projectIds, Instant from, Instant to) {
        return countAll(workspaceId, projectIds, from, to)
                .onErrorResume(error -> {
                    log.warn("Topics window count failed, counting each project; workspace '{}', projects '{}'",
                            workspaceId, projectIds.size(), error);
                    return Flux.fromIterable(projectIds)
                            .concatMap(projectId -> countAll(workspaceId, List.of(projectId), from, to)
                                    .onErrorResume(projectError -> {
                                        log.warn("Topics window count failed, counted again the next plan; "
                                                + "workspace '{}', project '{}'", workspaceId, projectId, projectError);
                                        return Mono.just(Map.of());
                                    }))
                            .collect(LinkedHashMap<UUID, Long>::new, Map::putAll);
                });
    }

    /** Every project of {@code projectIds}, 0 when it has no vector in the window. */
    private Mono<Map<UUID, Long>> countAll(String workspaceId, List<UUID> projectIds, Instant from, Instant to) {
        return windowDAO.countByProject(workspaceId, projectIds, from, to, countSeconds())
                .collectMap(TopicWindowDAO.ProjectCount::projectId, TopicWindowDAO.ProjectCount::traces,
                        LinkedHashMap::new)
                .map(counts -> {
                    projectIds.forEach(projectId -> counts.putIfAbsent(projectId, 0L));
                    return counts;
                });
    }

    /** One item of each list in turn, in the lists' order; a shorter list drops out when it runs out. */
    static <T> List<T> roundRobin(@NonNull List<List<T>> lists) {
        int longest = lists.stream().mapToInt(List::size).max().orElse(0);
        return IntStream.range(0, longest)
                .boxed()
                .flatMap(i -> lists.stream().filter(list -> i < list.size()).map(list -> list.get(i)))
                .toList();
    }

    private long countSeconds() {
        return config.getRun().getCountTimeout().toJavaDuration().toSeconds();
    }

    private Instant windowFrom(LocalDate night) {
        return night.minusDays(config.getRun().getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant windowTo(LocalDate night) {
        return night.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
