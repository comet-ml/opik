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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    Mono<Optional<ListlessProject>> findListless(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findListless(List.of(workspaceId), windowFrom(night))))
                .subscribeOn(Schedulers.boundedElastic())
                .map(rows -> rows.stream().filter(row -> row.projectId().equals(projectId)).findFirst());
    }

    static Duration retryDelay(@NonNull List<Duration> delays, int failures) {
        return delays.get(Math.min(failures, delays.size()) - 1);
    }

    /** Whether the project's last failed discovery run, if any, is older than its retry delay. */
    boolean retryDue(@NonNull ListlessProject project, @NonNull Instant now) {
        if (project.failedRuns() == 0 || project.runFinishedAt() == null) {
            return true;
        }
        var delays = config.getDiscovery().retryDelays();
        return !project.runFinishedAt().plus(retryDelay(delays, project.failedRuns())).isAfter(now);
    }

    /**
     * Of the listless projects, by workspace, those whose retry is due and whose gate is open. A workspace with
     * projects whose gate timed out closed (a stale workspace, or a backfill past {@code backfillMaxWait} with too few
     * vectors) is logged once.
     */
    private Map<String, List<UUID>> open(List<ListlessProject> listless, Instant now) {
        var open = new LinkedHashMap<String, List<UUID>>();
        var overdue = new LinkedHashMap<String, List<ListlessProject>>();
        for (var project : listless) {
            if (!retryDue(project, now)) {
                continue;
            }
            var state = backfillGate.discoveryGate(project, now);
            if (state.isOpen()) {
                open.computeIfAbsent(project.workspaceId(), key -> new ArrayList<>()).add(project.projectId());
            } else if (state == TopicsBackfillGate.State.TIMED_OUT_WAITING) {
                overdue.computeIfAbsent(project.workspaceId(), key -> new ArrayList<>()).add(project);
            }
        }
        overdue.forEach((workspaceId, projects) -> log.warn(
                "Topics discovery runs held back: the vectors tick stopped checking the workspace, or a backfill ran "
                        + "past topics.discovery.backfillMaxWait '{}' with too few traces embedded, workspace '{}', "
                        + "projects '{}', longest wait seconds '{}'",
                config.getDiscovery().getBackfillMaxWait(), workspaceId, projects.size(),
                projects.stream().map(project -> TopicsBackfillGate.waited(project, now)).max(Comparator.naturalOrder())
                        .orElse(Duration.ZERO).toSeconds()));
        return open;
    }

    /**
     * The workspace's projects with enough vectors, the most first. One count for the workspace; when it fails (a
     * large workspace can trip {@code max_rows_to_read}), one per project, so a large project fails only its own.
     */
    private Mono<List<Candidate>> count(String workspaceId, List<UUID> projectIds, Instant from, Instant to) {
        return countAll(workspaceId, projectIds, from, to)
                .onErrorResume(error -> {
                    log.warn("Topics discovery runs count failed, workspace '{}', projects '{}'; counting each project",
                            workspaceId, projectIds.size(), error);
                    return Flux.fromIterable(projectIds)
                            .concatMap(projectId -> countAll(workspaceId, List.of(projectId), from, to)
                                    .onErrorResume(projectError -> {
                                        log.warn("Topics discovery runs count failed, workspace '{}', project '{}'; it "
                                                + "is counted again the next plan", workspaceId, projectId,
                                                projectError);
                                        return Mono.just(Map.of());
                                    }))
                            .collect(LinkedHashMap<UUID, Long>::new, Map::putAll);
                })
                .map(counts -> counts.entrySet().stream()
                        .filter(entry -> entry.getValue() >= config.getRun().getMinVectors())
                        .map(entry -> Candidate.builder().workspaceId(workspaceId).projectId(entry.getKey())
                                .vectors(entry.getValue()).build())
                        .sorted(Comparator.comparingLong(Candidate::vectors).reversed()
                                .thenComparing(Candidate::projectId))
                        .toList());
    }

    private Mono<Map<UUID, Long>> countAll(String workspaceId, List<UUID> projectIds, Instant from, Instant to) {
        return windowDAO.countByProject(workspaceId, projectIds, from, to, countSeconds())
                .collectMap(TopicWindowDAO.ProjectCount::projectId, TopicWindowDAO.ProjectCount::traces);
    }

    /** One project of each workspace in turn, in the workspaces' order. */
    static List<Candidate> roundRobin(@NonNull List<List<Candidate>> byWorkspace) {
        var result = new ArrayList<Candidate>();
        int longest = byWorkspace.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (var candidates : byWorkspace) {
                if (i < candidates.size()) {
                    result.add(candidates.get(i));
                }
            }
        }
        return result;
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
