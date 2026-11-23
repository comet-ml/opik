import { QueryClient } from "@tanstack/react-query";

import useDatasetVersionByHash, {
  getDatasetVersionByHash,
} from "@/api/datasets/useDatasetVersionByHash";
import { DatasetVersion, Evaluator, Experiment } from "@/types/datasets";
import { MetricType } from "@/types/test-suites";

export const getExpectedAssertionCount = (evaluators?: Evaluator[]): number => {
  if (!evaluators) return 0;
  return evaluators.reduce((sum, ev) => {
    if (ev.type === MetricType.LLM_AS_JUDGE) {
      const schema = (ev.config as { schema?: unknown[] })?.schema;
      return sum + (Array.isArray(schema) ? schema.length : 0);
    }
    return sum;
  }, 0);
};

// A version never changes once written, so it is fetched once per run.
const VERSION_STALE_TIME = Infinity;

/**
 * How many assertions the suite version a run used applies to every item. They live on the version, not on
 * each item, so the experiment rows don't carry them. Undefined while unknown, including when the version can't
 * be read, so callers wait rather than count items as scored too early. 0 for an experiment with no version.
 */
export const useSuiteAssertionCount = (
  datasetId: string,
  experiment: Experiment | undefined,
): number | undefined => {
  const versionHash = experiment?.dataset_version_summary?.version_hash;
  const { data } = useDatasetVersionByHash(
    { datasetId, versionHash: versionHash ?? "" },
    { enabled: !!datasetId && !!versionHash, staleTime: VERSION_STALE_TIME },
  );
  if (experiment && !versionHash) return 0;
  return data ? getExpectedAssertionCount(data.evaluators) : undefined;
};

/** The same for a polling loop, where hooks can't run. Undefined if the version can't be read. */
export const fetchSuiteAssertionCount = async (
  queryClient: QueryClient,
  datasetId: string,
  versionHash: string | undefined,
): Promise<number | undefined> => {
  if (!datasetId || !versionHash) return 0;
  try {
    const version = await queryClient.fetchQuery<DatasetVersion>({
      queryKey: ["dataset-versions", { datasetId, versionHash }],
      queryFn: (context) =>
        getDatasetVersionByHash(context, { datasetId, versionHash }),
      staleTime: VERSION_STALE_TIME,
    });
    return getExpectedAssertionCount(version.evaluators);
  } catch {
    return undefined;
  }
};
