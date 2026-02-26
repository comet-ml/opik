import { useMemo } from "react";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";
import {
  expandEvaluatorsToRows,
  applyBehaviorEdits,
  formatEvaluatorConfig,
} from "@/lib/evaluator-converters";

export { formatEvaluatorConfig as getConfigTooltip };

export function useEvaluatorDisplayRows(
  serverEvaluators: Evaluator[],
  addedEvaluators: Map<string, BehaviorDisplayRow>,
  editedEvaluators: Map<string, Partial<BehaviorDisplayRow>>,
  deletedEvaluatorIds: Set<string>,
): BehaviorDisplayRow[] {
  const serverRows = useMemo(
    () => expandEvaluatorsToRows(serverEvaluators),
    [serverEvaluators],
  );

  return useMemo(
    () =>
      applyBehaviorEdits(
        serverRows,
        addedEvaluators,
        editedEvaluators,
        deletedEvaluatorIds,
      ),
    [serverRows, addedEvaluators, editedEvaluators, deletedEvaluatorIds],
  );
}
