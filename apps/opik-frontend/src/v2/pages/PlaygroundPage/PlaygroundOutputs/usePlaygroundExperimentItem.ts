import { useMemo } from "react";
import get from "lodash/get";

import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import {
  useDatasetFilters,
  useDatasetPage,
  useDatasetSize,
} from "@/store/PlaygroundStore";
import { transformDataColumnFilters } from "@/lib/filters";

const REFETCH_INTERVAL = 1000;

export type PlaygroundExperimentItem = {
  /** False while the row is still being processed, which is what the cell shows a loader for. */
  hasItem: boolean;
  output: string | null;
  error: string | null;
  traceId: string | null;
  runCount: number;
};

const EMPTY_ITEM: PlaygroundExperimentItem = {
  hasItem: false,
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

  const { data } = useCompareExperimentsList(
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
        return hasItem ? false : REFETCH_INTERVAL;
      },
    },
  );

  return useMemo(() => {
    if (!experimentId) return EMPTY_ITEM;

    const items = (data?.content ?? [])
      .flatMap((row) => row.experiment_items ?? [])
      .filter((ei) => ei.dataset_item_id === datasetItemId)
      .sort((a, b) => b.created_at.localeCompare(a.created_at));

    const latest = items[0];
    if (!latest) return EMPTY_ITEM;

    // A row whose provider call failed carries no output; the backend puts the fault under
    // `output.error` instead, which is where the cell's error state comes from.
    const output = get(latest, ["output", "output"], null);
    const error = get(latest, ["output", "error", "message"], null);

    return {
      hasItem: true,
      output: output === null ? null : String(output),
      error: error === null ? null : String(error),
      traceId: latest.trace_id ?? null,
      runCount: items.length,
    };
  }, [data?.content, datasetItemId, experimentId]);
}
