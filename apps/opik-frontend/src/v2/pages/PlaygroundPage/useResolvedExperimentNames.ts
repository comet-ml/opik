import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import uniq from "lodash/uniq";

import { getExperimentsList } from "@/api/datasets/useExperimentsList";
import { usePromptIds, usePromptMap } from "@/store/PlaygroundStore";
import {
  buildExperimentNameBase,
  composeExperimentName,
} from "@/lib/experimentNaming";

/**
 * Resolves the experiment name each prompt would produce, ahead of the run, so the
 * toolbar can preview exactly what the Experiments list will show.
 *
 * The run number counts experiments already named with the same base. Two tabs resolving
 * at once can land on the same number; that is acceptable because experiment names carry
 * no uniqueness constraint and duplicates are already reachable via the SDK.
 */
const useResolvedExperimentNames = (enabled: boolean) => {
  const promptIds = usePromptIds();
  const promptMap = usePromptMap();

  const basesByPromptId = useMemo(
    () =>
      promptIds.map((promptId, index) =>
        buildExperimentNameBase(
          promptMap[promptId]?.experimentLabel ?? "",
          index,
        ),
      ),
    [promptIds, promptMap],
  );

  const uniqueBases = useMemo(() => uniq(basesByPromptId), [basesByPromptId]);

  const results = useQueries({
    queries: uniqueBases.map((base) => ({
      // Nested under "experiments" so the invalidation the run paths already fire
      // refreshes the count, otherwise a re-run reuses the cached number.
      queryKey: ["experiments", "name-count", base],
      queryFn: (context: Parameters<typeof getExperimentsList>[0]) =>
        getExperimentsList(context, { search: base, page: 1, size: 1 }),
      enabled,
      staleTime: 0,
    })),
  });

  return useMemo(() => {
    const countByBase = new Map<string, number>();
    uniqueBases.forEach((base, index) => {
      countByBase.set(base, results[index]?.data?.total ?? 0);
    });

    // Prompts sharing a base (same custom label) each need their own number.
    const usedByBase = new Map<string, number>();
    const namesByPromptId: Record<string, string> = {};

    promptIds.forEach((promptId, index) => {
      const base = basesByPromptId[index];
      const offset = usedByBase.get(base) ?? 0;
      usedByBase.set(base, offset + 1);
      namesByPromptId[promptId] = composeExperimentName(
        base,
        (countByBase.get(base) ?? 0) + offset + 1,
      );
    });

    return {
      namesByPromptId,
      isLoading: results.some((result) => result.isLoading),
    };
  }, [promptIds, basesByPromptId, uniqueBases, results]);
};

export default useResolvedExperimentNames;
