package com.comet.opik.domain;

import java.util.UUID;

/**
 * The subset of an experiment the write path validates against.
 *
 * <p>The bulk-ingestion path only needs to answer two questions about an experiment that already
 * exists — does it belong to the dataset the request names, and to the project the request names —
 * yet it used to obtain them from {@code ExperimentService.getById}, which computes the full UI read
 * model: aggregation branch counts, the FIND CTE chain over experiments and experiment aggregates,
 * enrichment, and a lazy-aggregation trigger. That is run once per batch, so a single upload paid
 * for it repeatedly while consuming three fields.
 *
 * <p>Ids rather than names: {@code experiments} stores {@code dataset_id} and {@code project_id};
 * the names are produced by enrichment joins, which is precisely the cost being avoided. A dataset
 * id is what the row actually holds and two datasets cannot share one, so comparing ids is both
 * cheaper and no weaker than comparing names. Names are resolved lazily, only on the branches that
 * genuinely need them (an error message, or deriving a project when the request omits one).
 */
public record ExperimentWriteContext(UUID id, UUID datasetId, UUID projectId) {
}
