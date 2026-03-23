import { useMemo } from "react";
import { useSuiteAssertions } from "@/store/EvaluationSuiteDraftStore";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { extractAssertions } from "@/lib/assertion-converters";

export const useEffectiveSuiteAssertions = (suiteId: string): string[] => {
  const draftAssertions = useSuiteAssertions();
  const { data: versionsData } = useDatasetVersionsList({
    datasetId: suiteId,
    page: 1,
    size: 1,
  });

  return useMemo(() => {
    if (draftAssertions !== null) return draftAssertions;
    const evaluators = versionsData?.content?.[0]?.evaluators ?? [];
    return extractAssertions(evaluators);
  }, [draftAssertions, versionsData]);
};
