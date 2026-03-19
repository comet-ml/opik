import { useMemo } from "react";
import { useItemAssertions } from "@/store/EvaluationSuiteDraftStore";
import { extractAssertions } from "@/lib/assertion-converters";
import { Evaluator } from "@/types/datasets";

export const useEffectiveItemAssertions = (
  itemId: string,
  serverEvaluators: Evaluator[],
): string[] => {
  const draftAssertions = useItemAssertions(itemId);

  return useMemo(() => {
    if (draftAssertions != null) return draftAssertions;
    return extractAssertions(serverEvaluators);
  }, [draftAssertions, serverEvaluators]);
};
