import { useMemo } from "react";
import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { AxiosError, HttpStatusCode } from "axios";
import uniq from "lodash/uniq";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { COMPARE_EXPERIMENTS_MAX_PAGE_SIZE } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import {
  useExperimentByPromptId,
  useRunExperimentIdsByPromptId,
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

const isNotFound = (error: unknown) =>
  (error as AxiosError | null)?.response?.status === HttpStatusCode.NotFound;

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

export const areAllRowItemsScored = (
  row: ExperimentsCompare,
  experimentId?: string,
): boolean => {
  const items = (row.experiment_items ?? []).filter(
    (ei) => !experimentId || ei.experiment_id === experimentId,
  );
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
  const runExperimentIdsByPromptId = useRunExperimentIdsByPromptId();
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

  // A prompt without its own run entry ran together with every current one.
  const runIdsByPromptId = useMemo(
    () =>
      Object.fromEntries(
        entries.map(([promptId]) => [
          promptId,
          runExperimentIdsByPromptId[promptId] ?? experimentIds,
        ]),
      ),
    [entries, experimentIds, runExperimentIdsByPromptId],
  );

  const fetchedExperimentIds = useMemo(
    () => uniq([...experimentIds, ...Object.values(runIdsByPromptId).flat()]),
    [experimentIds, runIdsByPromptId],
  );

  const experimentStatusResults = useQueries({
    queries: fetchedExperimentIds.map((experimentId) => ({
      queryKey: ["experiment", { experimentId }],
      queryFn: (context: QueryFunctionContext) =>
        getExperimentById(context, { experimentId }),
      enabled: isTestSuite && !!experimentId,
      refetchInterval: (query: {
        state: { data: unknown; error: unknown };
      }) => {
        if (isNotFound(query.state.error)) return false;
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

  const experimentById = useMemo(
    () =>
      Object.fromEntries(
        fetchedExperimentIds.map((id, i) => [
          id,
          experimentStatusResults[i]?.data as Experiment | undefined,
        ]),
      ),
    [fetchedExperimentIds, experimentStatusResults],
  );

  const missingExperimentIds = useMemo(
    () =>
      new Set(
        fetchedExperimentIds.filter((_, i) =>
          isNotFound(experimentStatusResults[i]?.error),
        ),
      ),
    [fetchedExperimentIds, experimentStatusResults],
  );

  const allExperimentsFinished = useMemo(() => {
    if (experimentIds.length === 0) return false;
    return experimentIds.every((id) =>
      isExperimentTerminal(
        experimentById[id]?.status as EXPERIMENT_STATUS | undefined,
      ),
    );
  }, [experimentIds, experimentById]);

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
        return rows.every((row) => areAllRowItemsScored(row))
          ? false
          : REFETCH_INTERVAL;
      },
    },
  );

  const scoredExperimentIds = useMemo(() => {
    const rows = compareData?.content ?? [];
    return new Set(
      fetchedExperimentIds.filter((id) => {
        const data = experimentById[id];
        if (!data) return false;
        if (isExperimentTerminal(data.status as EXPERIMENT_STATUS)) {
          return true;
        }
        return (
          rows.length > 0 && rows.every((row) => areAllRowItemsScored(row, id))
        );
      }),
    );
  }, [compareData?.content, fetchedExperimentIds, experimentById]);

  return useMemo(() => {
    if (!isTestSuite || entries.length === 0) return null;

    return Object.fromEntries(
      entries.flatMap(([promptId, experimentId]) => {
        // A deleted experiment from an older run must not keep this prompt
        // uncolored forever.
        const runIds = runIdsByPromptId[promptId].filter(
          (id) => id === experimentId || !missingExperimentIds.has(id),
        );
        if (!runIds.every((id) => scoredExperimentIds.has(id))) return [];

        const passRates = runIds
          .map((id) => experimentById[id]?.pass_rate)
          .filter((r): r is number => r != null);

        const maxPassRate =
          passRates.length > 0 ? Math.max(...passRates) : undefined;

        const data = experimentById[experimentId];
        const passRate = data?.pass_rate;

        return [
          [
            promptId,
            {
              passRate,
              passedCount: data?.passed_count,
              totalCount: data?.total_count,
              isWinner: maxPassRate != null && passRate === maxPassRate,
            },
          ],
        ];
      }),
    );
  }, [
    isTestSuite,
    entries,
    runIdsByPromptId,
    missingExperimentIds,
    scoredExperimentIds,
    experimentById,
  ]);
}
