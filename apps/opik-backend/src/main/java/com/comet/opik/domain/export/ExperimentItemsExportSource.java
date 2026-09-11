package com.comet.opik.domain.export;

import com.comet.opik.api.AssertionResult;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsExportParams;
import com.comet.opik.api.ExportParams;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.domain.DatasetItemDAO;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports the results of one or more experiments over a dataset: one row per dataset item, with each compared
 * experiment's output, scores and assertions flattened into prefixed columns.
 *
 * <p>Column discovery is data-driven rather than schema-driven — the set of feedback scores and assertions varies
 * per item — so a first pass over the rows collects the column set before the CSV is written. Rows are then streamed
 * a second time by keyset cursor, so neither pass holds the result set in memory.</p>
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ExperimentItemsExportSource implements ExportSource {

    private static final String DATASET_PREFIX = "dataset.";
    private static final String FEEDBACK_SCORES_PREFIX = "feedback_scores.";

    private final @NonNull DatasetItemDAO datasetItemDao;
    private final @NonNull ExperimentService experimentService;

    @Override
    public String exportType() {
        return ExperimentItemsExportParams.TYPE;
    }

    @Override
    public Class<? extends ExportParams> paramsType() {
        return ExperimentItemsExportParams.class;
    }

    @Override
    public Mono<List<String>> discoverColumns(@NonNull ExportParams params) {
        var exportParams = cast(params, ExperimentItemsExportParams.class);

        return experimentNames(exportParams)
                .flatMap(names -> streamItems(exportParams)
                        .reduce(new LinkedHashSet<String>(), (columns, item) -> {
                            columns.addAll(rowFor(item, names).keySet());
                            return columns;
                        })
                        .map(List::copyOf));
    }

    @Override
    public Flux<SequencedMap<String, String>> streamRows(@NonNull ExportParams params, int batchSize) {
        var exportParams = cast(params, ExperimentItemsExportParams.class);

        return experimentNames(exportParams)
                .flatMapMany(names -> streamItems(exportParams, batchSize).map(item -> rowFor(item, names)));
    }

    /**
     * Experiment id to column prefix. Names are only unique by convention, so a duplicate name falls back to
     * including the id — the same disambiguation the UI does.
     */
    private Mono<Map<UUID, String>> experimentNames(ExperimentItemsExportParams params) {
        return Flux.fromIterable(params.experimentIds())
                .concatMap(experimentService::getById)
                .collectList()
                .map(experiments -> {
                    boolean allUnique = experiments.stream().map(Experiment::name).distinct()
                            .count() == experiments.size();

                    Map<UUID, String> names = new LinkedHashMap<>();
                    experiments.forEach(experiment -> names.put(experiment.id(),
                            allUnique ? experiment.name() : "%s(%s)".formatted(experiment.name(), experiment.id())));
                    return names;
                });
    }

    private Flux<DatasetItem> streamItems(ExperimentItemsExportParams params) {
        return streamItems(params, 100);
    }

    private Flux<DatasetItem> streamItems(ExperimentItemsExportParams params, int batchSize) {
        var criteria = DatasetItemSearchCriteria.builder()
                .datasetId(params.datasetId())
                .experimentIds(Set.copyOf(params.experimentIds()))
                .entityType(EntityType.TRACE)
                .truncate(false)
                .versionHashOrTag(null)
                .build();

        return streamPage(criteria, new AtomicReference<>(), batchSize);
    }

    private Flux<DatasetItem> streamPage(DatasetItemSearchCriteria criteria, AtomicReference<UUID> cursor,
            int batchSize) {
        return Flux.defer(() -> datasetItemDao.getExperimentItemsForExport(criteria, batchSize, cursor.get())
                .collectList()
                .flatMapMany(items -> {
                    if (items.isEmpty()) {
                        return Flux.empty();
                    }

                    cursor.set(items.getLast().id());

                    Flux<DatasetItem> page = Flux.fromIterable(items);

                    // A full page means there may be more; a partial page is the last one.
                    return items.size() == batchSize
                            ? page.concatWith(streamPage(criteria, cursor, batchSize))
                            : page;
                }));
    }

    /**
     * Flattens one dataset item and its experiment items into CSV cells. Mirrors the shape the synchronous
     * frontend export produces, so both paths yield comparable files.
     */
    private SequencedMap<String, String> rowFor(DatasetItem item, Map<UUID, String> experimentNames) {
        SequencedMap<String, String> row = new LinkedHashMap<>();

        if (item.data() != null) {
            item.data().forEach((key, value) -> row.put(DATASET_PREFIX + key, stringify(value)));
        }

        boolean isCompare = experimentNames.size() > 1;

        for (ExperimentItem experimentItem : emptyIfNull(item.experimentItems())) {
            String prefix = isCompare
                    ? experimentNames.getOrDefault(experimentItem.experimentId(), "unknown") + "."
                    : "";

            row.put(prefix + "output", stringify(experimentItem.output()));
            row.put(prefix + "duration", asString(experimentItem.duration()));
            row.put(prefix + "total_estimated_cost", asString(experimentItem.totalEstimatedCost()));

            if (experimentItem.usage() != null) {
                experimentItem.usage()
                        .forEach((key, value) -> row.put(prefix + "usage." + key, asString(value)));
            }

            for (FeedbackScore score : emptyIfNull(experimentItem.feedbackScores())) {
                row.put(prefix + FEEDBACK_SCORES_PREFIX + score.name(), asString(score.value()));
                if (score.reason() != null) {
                    row.put(prefix + FEEDBACK_SCORES_PREFIX + score.name() + "_reason", score.reason());
                }
            }

            row.put(prefix + "status", asString(experimentItem.status()));

            List<AssertionResult> assertions = emptyIfNull(experimentItem.assertionResults());
            for (int i = 0; i < assertions.size(); i++) {
                AssertionResult assertion = assertions.get(i);
                String assertionPrefix = "%sassertion_%d.".formatted(prefix, i + 1);

                row.put(assertionPrefix + "name", asString(assertion.value()));
                row.put(assertionPrefix + "result", assertion.passed() ? "passed" : "failed");
                if (assertion.reason() != null) {
                    row.put(assertionPrefix + "reason", assertion.reason());
                }
            }
        }

        return row;
    }

    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String stringify(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }

        return value.isTextual() ? value.asText() : JsonUtils.writeValueAsString(value);
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }
}
