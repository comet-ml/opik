import { useMemo } from "react";
import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import {
  useExperimentByPromptId,
  useDatasetType,
} from "@/store/PlaygroundStore";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";
import { DATASET_TYPE, Experiment, ExperimentsCompare } from "@/types/datasets";
import { extractAssertions } from "@/lib/assertion-converters";

const REFETCH_INTERVAL = 5000;

export const areAllRowItemsScored = (row: ExperimentsCompare): boolean => {
  const evaluators = row.evaluators;
  const expectedCount = extractAssertions(evaluators ?? []).length;
  const items = row.experiment_items ?? [];

  if (items.length === 0) return false;

  if (expectedCount === 0) {
    if (evaluators && evaluators.length === 0) return true;
    return items.every((ei) => ei.status != null);
  }

  return items.every(
    (ei) => (ei.assertion_results?.length ?? 0) >= expectedCount,
  );
};

export type PromptResult = {
  passRate: number | undefined;
  passedCount: number | undefined;
  totalCount: number | undefined;
  isWinner: boolean;
};

export default function useTestSuitePromptResults(): Record<
  string,
  PromptResult
> | null {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const experimentByPromptId = useExperimentByPromptId();
  const datasetType = useDatasetType();
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;

  const { datasetId: versionedDatasetId } = usePlaygroundDataset();
  const plainDatasetId =
    parseDatasetVersionKey(versionedDatasetId)?.datasetId || versionedDatasetId;

  const entries = useMemo(
    () => Object.entries(experimentByPromptId),
    [experimentByPromptId],
  );

  const experimentIds = useMemo(() => entries.map(([, id]) => id), [entries]);

  const { data: compareData } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId: plainDatasetId || "",
      experimentsIds: experimentIds,
      page: 1,
      size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
      truncate: true,
    },
    {
      enabled: isTestSuite && experimentIds.length > 0 && !!plainDatasetId,
      refetchInterval: (query) => {
        const rows = query.state.data?.content ?? [];
        if (rows.length === 0) return REFETCH_INTERVAL;

        return rows.every(areAllRowItemsScored) ? false : REFETCH_INTERVAL;
      },
    },
  );

  const allItemsScored = useMemo(() => {
    const rows = compareData?.content ?? [];
    if (rows.length === 0) return false;
    return rows.every(areAllRowItemsScored);
  }, [compareData?.content]);

  const experimentResults = useQueries({
    queries: entries.map(([, experimentId]) => ({
      queryKey: ["experiment", { experimentId }],
      queryFn: (context: QueryFunctionContext) =>
        getExperimentById(context, { experimentId }),
      enabled: isTestSuite && !!experimentId && allItemsScored,
      refetchInterval: (query: { state: { data: unknown } }) => {
        if (!allItemsScored) return false;
        const data = query.state.data as Experiment | undefined;
        return data?.pass_rate != null ? false : REFETCH_INTERVAL;
      },
    })),
  });

  return useMemo(() => {
    if (!isTestSuite || entries.length === 0 || !allItemsScored) return null;

    const allLoaded = entries.every(
      (_, i) => experimentResults[i]?.data != null,
    );
    if (!allLoaded) return null;

    const experimentsData = entries.map(
      (_, i) => experimentResults[i]?.data as Experiment | undefined,
    );

    const passRates = experimentsData
      .map((d) => d?.pass_rate)
      .filter((r): r is number => r != null);

    const maxPassRate =
      passRates.length > 0 ? Math.max(...passRates) : undefined;

    return Object.fromEntries(
      entries.map(([promptId], i) => {
        const data = experimentsData[i];
        const passRate = data?.pass_rate;

        return [
          promptId,
          {
            passRate,
            passedCount: data?.passed_count,
            totalCount: data?.total_count,
            isWinner: maxPassRate != null && passRate === maxPassRate,
          },
        ];
      }),
    );
  }, [isTestSuite, entries, allItemsScored, experimentResults]);
}
