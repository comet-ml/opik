import { useMemo, useRef } from "react";
import get from "lodash/get";

import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useExperimentById from "@/api/datasets/useExperimentById";
import useAppStore from "@/store/AppStore";
import {
  useDatasetFilters,
  useDatasetPage,
  useDatasetSize,
} from "@/store/PlaygroundStore";
import { EXPERIMENT_STATUS, Experiment } from "@/types/datasets";
import { COLUMN_DATA_ID, COLUMN_TAGS_ID } from "@/types/shared";

const STATUS_REFETCH_INTERVAL = 1000;
const ROWS_REFETCH_INTERVAL = 3000;

/**
 * Whether the run has stopped producing items. A status cannot answer this: cancelling writes
 * CANCELLED the moment the stop is asked for, while items already with a provider are still
 * landing. finished_at is stamped only once the run actually drains, which is why the settling
 * logic below reads it rather than the status.
 */
const hasFinished = (experiment: Experiment | undefined) =>
  !!experiment?.finished_at ||
  // Transitional, for runs predating migration 000130, whose stamp is null for ever: remove once
  // they are gone or backfilled. CANCELLED stays out — it is written when the stop is asked for,
  // so it would settle rows whose calls are still landing.
  experiment?.status === EXPERIMENT_STATUS.COMPLETED ||
  experiment?.status === EXPERIMENT_STATUS.FAILED;

export type PlaygroundExperimentItem = {
  /** False while the row is still being processed, which is what the cell shows a loader for. */
  hasItem: boolean;
  notRun: boolean;
  cancelled: boolean;
  output: string | null;
  error: string | null;
  traceId: string | null;
  runCount: number;
};

const EMPTY_ITEM: PlaygroundExperimentItem = {
  hasItem: false,
  notRun: false,
  cancelled: false,
  output: null,
  error: null,
  traceId: null,
  runCount: 0,
};

/**
 * What a server-side run produced for one cell, read back from the experiment items.
 *
 * Scoped to the table's page rather than the whole dataset a run now covers, and keyed so every
 * cell in a column shares one request.
 *
 * Filtered the same way as the table, or the two disagree about which rows a page holds and a row
 * that ran reads as never run. Dotted rather than the {field, key} pair the dataset-items endpoint
 * takes, since this one resolves the column from the name alone. Only the fields it knows are
 * sent; anything else would be read as a data key and match nothing.
 *
 * Polling stops once the row has its item, or once the run's finish stamp says none is coming.
 * Without that second condition a row the run never reached waits forever.
 */
export default function usePlaygroundExperimentItem(
  experimentId: string | undefined,
  datasetItemId: string,
  datasetId: string,
): PlaygroundExperimentItem {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const page = useDatasetPage();
  const size = useDatasetSize();
  const datasetFilters = useDatasetFilters();
  const supportedFilters = useMemo(
    () =>
      datasetFilters.filter(
        (filter) =>
          filter.field.startsWith(`${COLUMN_DATA_ID}.`) ||
          filter.field === COLUMN_TAGS_ID,
      ),
    [datasetFilters],
  );

  // Polling stops at the stamp — a finished run has nothing left to report.
  const { data: experiment, dataUpdatedAt: experimentReadAt } =
    useExperimentById(
      { experimentId: experimentId! },
      {
        enabled: !!experimentId,
        // A finished run has nothing left to tell us, so it never goes stale and a cell scrolling
        // back into view refetches nothing.
        staleTime: (query) => (hasFinished(query.state.data) ? Infinity : 0),
        refetchInterval: (query) =>
          hasFinished(query.state.data) ? false : STATUS_REFETCH_INTERVAL,
      },
    );

  const finished = hasFinished(experiment);

  // The read that first told us this run had finished. Pinned rather than taken fresh: the run is
  // read again whenever a cell scrolls back, and anything else watching it can refresh it too, so a
  // reference that moved would put the loader back on a row that had already settled. Held against
  // the experiment it was made for — a re-run gives the cell a new id, and a finished one is served
  // from cache, so carrying the old observation over would settle its rows against a stranger's.
  const finishedObserved = useRef<{ experimentId: string; at: number } | null>(
    null,
  );
  if (!finished || finishedObserved.current?.experimentId !== experimentId) {
    finishedObserved.current = null;
  }
  if (finished && experimentId) {
    finishedObserved.current ??= { experimentId, at: experimentReadAt };
  }

  const wasReadAfterFinish = (rowsAt: number) =>
    finishedObserved.current !== null && rowsAt >= finishedObserved.current.at;

  const { data, dataUpdatedAt: rowsReadAt } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds: experimentId ? [experimentId] : [],
      page,
      size,
      truncate: false,
      filters: supportedFilters,
    },
    {
      enabled: !!experimentId && !!datasetId,
      refetchInterval: (query) => {
        const rows = query.state.data?.content ?? [];
        const hasItem = rows.some(
          (row) =>
            row.experiment_items?.some(
              (ei) => ei.dataset_item_id === datasetItemId,
            ),
        );
        // Rows read before that cannot show the items written just before it, so concluding from
        // them calls a row unrun and then contradicts itself. One more read settles it.
        return hasItem || wasReadAfterFinish(query.state.dataUpdatedAt)
          ? false
          : ROWS_REFETCH_INTERVAL;
      },
    },
  );

  const rowsReadAfterFinish = wasReadAfterFinish(rowsReadAt);

  return useMemo(() => {
    if (!experimentId) return EMPTY_ITEM;

    const items = (data?.content ?? [])
      .flatMap((row) => row.experiment_items ?? [])
      .filter((ei) => ei.dataset_item_id === datasetItemId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));

    const latest = items[0];
    if (!latest) {
      return rowsReadAfterFinish
        ? {
            ...EMPTY_ITEM,
            notRun: true,
            cancelled: experiment?.status === EXPERIMENT_STATUS.CANCELLED,
          }
        : EMPTY_ITEM;
    }

    // A row whose provider call failed carries no output; ExperimentTracePersistence puts the
    // fault under `output.error` as `{type?, message}`, which is the cell's error state.
    const output = get(latest, ["output", "output"], null);
    const error = get(latest, ["output", "error", "message"], null);

    return {
      hasItem: true,
      notRun: false,
      cancelled: false,
      output: output === null ? null : String(output),
      error: error === null ? null : String(error),
      traceId: latest.trace_id ?? null,
      runCount: items.length,
    };
  }, [
    data?.content,
    datasetItemId,
    experimentId,
    rowsReadAfterFinish,
    experiment?.status,
  ]);
}
