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
import { transformDataColumnFilters } from "@/lib/filters";
import { EXPERIMENT_STATUS, Experiment } from "@/types/datasets";

const REFETCH_INTERVAL = 1000;

/**
 * Whether the run has stopped producing items. A status cannot answer this: cancelling writes one
 * the moment the stop is asked for, while items already with a provider are still landing. The
 * backend stamps this when the run actually drains.
 */
const hasFinished = (experiment: Experiment | undefined) =>
  !!experiment?.finished_at;

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
 * The query is scoped to the same page and filters as the dataset table rather than to the whole
 * dataset: a run now covers every matching item, so asking for all of them — untruncated, on every
 * poll — would grow with the dataset while the user can only ever see one page of it.
 *
 * Every cell in a column shares the query key, so they share one request; polling stops once each
 * cell's own row has an item, since a cell still waiting keeps its own interval alive.
 *
 * A row the run never reached would otherwise wait for ever — stopping a run leaves its unprocessed
 * items without an experiment item, and nothing will ever write one. The run's own finish stamp is
 * the second stop condition: past it, a row with no item never had one coming.
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

  const filters = useMemo(
    () => transformDataColumnFilters(datasetFilters),
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
          hasFinished(query.state.data) ? false : REFETCH_INTERVAL,
      },
    );

  const finished = hasFinished(experiment);

  // The read that first told us the run had finished. Pinned rather than taken fresh: the run is
  // read again whenever a cell scrolls back, and anything else watching it can refresh it too, so a
  // reference that moved would put the loader back on a row that had already settled.
  const knownFinishedAt = useRef<number | null>(null);
  if (!finished) knownFinishedAt.current = null;
  else knownFinishedAt.current ??= experimentReadAt;

  const readAfterFinish = (rowsAt: number) =>
    knownFinishedAt.current !== null && rowsAt >= knownFinishedAt.current;

  const { data, dataUpdatedAt: rowsReadAt } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds: experimentId ? [experimentId] : [],
      filters,
      page,
      size,
      truncate: false,
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
        return hasItem || readAfterFinish(query.state.dataUpdatedAt)
          ? false
          : REFETCH_INTERVAL;
      },
    },
  );

  const confirmedFinished = readAfterFinish(rowsReadAt);

  return useMemo(() => {
    if (!experimentId) return EMPTY_ITEM;

    const items = (data?.content ?? [])
      .flatMap((row) => row.experiment_items ?? [])
      .filter((ei) => ei.dataset_item_id === datasetItemId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));

    const latest = items[0];
    if (!latest) {
      return confirmedFinished
        ? {
            ...EMPTY_ITEM,
            notRun: true,
            cancelled: experiment?.status === EXPERIMENT_STATUS.CANCELLED,
          }
        : EMPTY_ITEM;
    }

    // A row whose provider call failed carries no output; the backend puts the fault under
    // `output.error` instead, which is where the cell's error state comes from.
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
    confirmedFinished,
    experiment?.status,
  ]);
}
