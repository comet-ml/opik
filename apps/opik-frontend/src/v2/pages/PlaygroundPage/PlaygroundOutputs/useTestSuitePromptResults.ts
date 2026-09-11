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
import {
  DATASET_TYPE,
  Evaluator,
  Experiment,
  EXPERIMENT_STATUS,
  ExperimentsCompare,
  ExperimentItem,
} from "@/types/datasets";
import { MetricType } from "@/types/test-suites";
import { isExperimentTerminal } from "@/lib/experiments";

const REFETCH_INTERVAL = 5000;

export const isItemScored = (ei: { status?: string | null }): boolean => {
  return ei.status != null;
};

const getExpectedAssertionCount = (evaluators?: Evaluator[]): number => {
  if (!evaluators) return 0;
  return evaluators.reduce((sum, ev) => {
    if (ev.type === MetricType.LLM_AS_JUDGE) {
      const schema = (ev.config as { schema?: unknown[] })?.schema;
      return sum + (Array.isArray(schema) ? schema.length : 0);
    }
    return sum;
  }, 0);
};

const isItemScoredByAssertions = (
  ei: ExperimentItem,
  expectedCount: number,
): boolean => {
  if (expectedCount > 0) {
    return (ei.assertion_results?.length ?? 0) >= expectedCount;
  }
  return isItemScored(ei);
};

export const areAllRowItemsScored = (row: ExperimentsCompare): boolean => {
  const items = row.experiment_items ?? [];
  if (items.length === 0) return false;

  if ((row.evaluators?.length ?? 0) === 0) return true;

  const expected = getExpectedAssertionCount(row.evaluators);
  return items.every((ei) => isItemScoredByAssertions(ei, expected));
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

  const experimentStatusResults = useQueries({
    queries: entries.map(([, experimentId]) => ({
      queryKey: ["experiment", { experimentId }],
      queryFn: (context: QueryFunctionContext) =>
        getExperimentById(context, { experimentId }),
      enabled: isTestSuite && !!experimentId,
      refetchInterval: (query: { state: { data: unknown } }) => {
        const data = query.state.data as Experiment | undefined;
        if (
          isExperimentTerminal(data?.status as EXPERIMENT_STATUS | undefined)
        ) {
          return data?.pass_rate != null ? false : REFETCH_INTERVAL;
        }
        return REFETCH_INTERVAL;
      },
    })),
  });

  const allExperimentsFinished = useMemo(() => {
    if (experimentStatusResults.length === 0) return false;
    return experimentStatusResults.every((r) => {
      const data = r.data as Experiment | undefined;
      return isExperimentTerminal(
        data?.status as EXPERIMENT_STATUS | undefined,
      );
    });
  }, [experimentStatusResults]);

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

        if (allExperimentsFinished) return false;
        return rows.every(areAllRowItemsScored) ? false : REFETCH_INTERVAL;
      },
    },
  );

  const allItemsScored = useMemo(() => {
    if (allExperimentsFinished) return true;
    const rows = compareData?.content ?? [];
    if (rows.length === 0) return false;
    return rows.every(areAllRowItemsScored);
  }, [compareData?.content, allExperimentsFinished]);

  return useMemo(() => {
    if (!isTestSuite || entries.length === 0 || !allItemsScored) return null;

    const allLoaded = entries.every(
      (_, i) => experimentStatusResults[i]?.data != null,
    );
    if (!allLoaded) return null;

    const experimentsData = entries.map(
      (_, i) => experimentStatusResults[i]?.data as Experiment | undefined,
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
  }, [isTestSuite, entries, allItemsScored, experimentStatusResults]);
}
