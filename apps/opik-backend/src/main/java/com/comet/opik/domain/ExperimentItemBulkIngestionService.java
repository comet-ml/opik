package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.Project;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.RetryUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;

@ImplementedBy(ExperimentItemBulkIngestionServiceImpl.class)
public interface ExperimentItemBulkIngestionService {

    /**
     * Ingests a batch of experiment items.
     *
     * @param experiment the experiment to add items to
     * @param projectName the project for traces auto-created from items that provide evaluate_task_result
     *                    (i.e. without an explicit trace); when blank, the default project is used
     * @param items the list of experiment items to ingest
     * @return a list of feedback score batch items
     */
    Mono<Void> ingest(Experiment experiment, String projectName, List<ExperimentItemBulkRecord> items);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentItemBulkIngestionServiceImpl implements ExperimentItemBulkIngestionService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Ingests a batch of experiment items.
     *
     * @param experiment the experiment to add items to
     * @param items the list of experiment items to ingest
     * @return a list of feedback score batch items
     */
    @Override
    public Mono<Void> ingest(Experiment experiment, String projectName, List<ExperimentItemBulkRecord> items) {

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Project applied to traces that don't carry their own project: traces auto-created from
            // evaluate_task_result items, and explicit traces with a blank project_name. When the request
            // does not specify one, fall back to the default project (deprecated behavior).
            String bulkProjectName = StringUtils.isNotBlank(projectName)
                    ? projectName
                    : ProjectService.DEFAULT_PROJECT;

            return validateExperimentConsistency(experiment)
                    .then(experimentService.create(experiment))
                    .retryWhen(RetryUtils.handleConnectionError())
                    .flatMap(experimentId -> {
                        log.info(
                                "Using experiment with id '{}', name '{}', datasetName '{}', projectName '{}', workspaceId '{}'",
                                experimentId, experiment.name(), experiment.datasetName(), experiment.projectName(),
                                workspaceId);

                        // Collect unique project names: the bulk project (for auto-created traces and
                        // traces without a project), plus any project specified on item-level traces.
                        List<String> projectNames = Stream.concat(
                                Stream.of(bulkProjectName),
                                items.stream()
                                        .map(ExperimentItemBulkRecord::trace)
                                        .filter(Objects::nonNull)
                                        .map(Trace::projectName)
                                        .filter(StringUtils::isNotBlank))
                                .distinct()
                                .toList();

                        // Resolve project names to project IDs upfront
                        return Flux.fromIterable(projectNames)
                                .flatMap(projectService::getOrCreate)
                                .collectMap(Project::name, Project::id,
                                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                                .flatMap(projectNameToIdMap -> {
                                    Set<ExperimentItem> experimentItems = new HashSet<>();
                                    List<Trace> traces = new ArrayList<>();
                                    List<Span> spans = new ArrayList<>();
                                    List<FeedbackScoreBatchItem> feedbackScores = new ArrayList<>();

                                    this.splitBatches(
                                            items,
                                            traces,
                                            experimentId,
                                            experimentItems,
                                            spans,
                                            feedbackScores,
                                            projectNameToIdMap,
                                            bulkProjectName);

                                    return experimentItemService.create(experimentItems)
                                            .then(saveAll(traces, spans, feedbackScores))
                                            .flatMap(tuple -> {

                                                log.info(
                                                        "Recorded experiment items in bulk, experiment items count '{}', traces count '{}', spans count '{}' and feedback scores count '{}'",
                                                        experimentItems.size(), traces.size(), spans.size(),
                                                        feedbackScores.size());

                                                return Mono.just(tuple);
                                            })
                                            .then();
                                });
                    });
        });
    }

    private Mono<Void> validateExperimentConsistency(Experiment experiment) {
        if (experiment.id() == null) {
            return Mono.empty(); // New experiment, no validation needed
        }

        return experimentService.getById(experiment.id())
                .flatMap(existingExperiment -> {
                    // Validate dataset consistency
                    if (!experiment.datasetName().equals(existingExperiment.datasetName())) {
                        String errorMessage = "Experiment '%s' belongs to dataset '%s', but request specifies dataset '%s'"
                                .formatted(experiment.id(), existingExperiment.datasetName(), experiment.datasetName());
                        return Mono.error(new ClientErrorException(errorMessage, Response.Status.CONFLICT));
                    }

                    return Mono.<Void>empty();
                })
                .onErrorResume(NotFoundException.class, ex -> {
                    // Experiment doesn't exist yet, validation passes
                    return Mono.empty();
                });
    }

    private Mono<? extends Tuple3<Long, ? extends Number, Integer>> saveAll(List<Trace> traces,
            List<Span> spans, List<FeedbackScoreBatchItem> feedbackScores) {
        return Mono.defer(() -> Mono.zip(
                saveTraces(traces),
                saveSpans(spans),
                saveFeedBackScores(feedbackScores)));
    }

    private Mono<Long> saveTraces(List<Trace> traces) {
        return traceService.create(new TraceBatch(traces))
                .retryWhen(RetryUtils.handleConnectionError());
    }

    private Mono<? extends Number> saveSpans(List<Span> spans) {
        return spans.isEmpty()
                ? Mono.just(0)
                : spanService.create(new SpanBatch(spans))
                        .retryWhen(RetryUtils.handleConnectionError());
    }

    private Mono<Integer> saveFeedBackScores(List<FeedbackScoreBatchItem> feedbackScores) {
        return feedbackScores.isEmpty()
                ? Mono.just(0)
                : feedbackScoreService.scoreBatchOfTraces(feedbackScores)
                        .retryWhen(RetryUtils.handleConnectionError())
                        .then(Mono.just(feedbackScores.size()));
    }

    private void splitBatches(List<ExperimentItemBulkRecord> items,
            List<Trace> traces, UUID experimentId,
            Set<ExperimentItem> experimentItems,
            List<Span> spans,
            List<FeedbackScoreBatchItem> feedbackScores,
            Map<String, UUID> projectNameToIdMap,
            String bulkProjectName) {

        for (ExperimentItemBulkRecord item : items) {
            Trace trace;

            // If the trace is null, create a new trace "invisible" to the user
            if (item.trace() == null) {
                Instant now = Instant.now();
                trace = Trace.builder()
                        .id(idGenerator.generateId())
                        .projectName(bulkProjectName)
                        .output(item.evaluateTaskResult())
                        .startTime(now)
                        .endTime(now)
                        .visibilityMode(VisibilityMode.HIDDEN)
                        .source(Source.EXPERIMENT)
                        .build();
            } else {
                var traceBuilder = item.trace().toBuilder()
                        .source(Source.EXPERIMENT);
                // An explicit trace without its own project inherits the bulk project.
                if (StringUtils.isBlank(item.trace().projectName())) {
                    traceBuilder.projectName(bulkProjectName);
                }
                trace = traceBuilder.build();
            }

            traces.add(trace);

            // Resolve project_id from projectName using the pre-built map
            UUID projectId = projectNameToIdMap.get(trace.projectName());

            ExperimentItem build = ExperimentItem.builder()
                    .id(idGenerator.generateId())
                    .experimentId(experimentId)
                    .datasetItemId(item.datasetItemId())
                    .traceId(trace.id())
                    .projectId(projectId)
                    .build();

            experimentItems.add(build);

            if (CollectionUtils.isNotEmpty(item.spans())) {
                // Spans belong to the item's trace (enforced by ExperimentItemBulkValidator), so they must
                // share its resolved project — otherwise span data would diverge from the trace's project.
                List<Span> experimentSpans = item.spans().stream()
                        .map(span -> span.toBuilder()
                                .source(Source.EXPERIMENT)
                                .projectName(trace.projectName())
                                .build())
                        .toList();

                spans.addAll(experimentSpans);
            }

            if (CollectionUtils.isNotEmpty(item.feedbackScores())) {
                feedbackScores.addAll(item.feedbackScores()
                        .stream()
                        .map(score -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(trace.id(),
                                trace.projectName(), score))
                        .toList());
            }
        }
    }

}
