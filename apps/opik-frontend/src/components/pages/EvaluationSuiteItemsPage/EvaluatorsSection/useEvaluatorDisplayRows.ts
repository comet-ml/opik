import { useMemo } from "react";
import { EvaluatorDisplayRow } from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";
import {
  expandEvaluatorsToRows,
  applyEvaluatorEdits,
  formatEvaluatorConfig,
} from "@/lib/evaluator-converters";

export { formatEvaluatorConfig as getConfigTooltip };

export function useEvaluatorDisplayRows(
  serverEvaluators: Evaluator[],
  addedEvaluators: Map<string, EvaluatorDisplayRow>,
  editedEvaluators: Map<string, Partial<EvaluatorDisplayRow>>,
  deletedEvaluatorIds: Set<string>,
): EvaluatorDisplayRow[] {
  const serverRows = useMemo(
    () => expandEvaluatorsToRows(serverEvaluators),
    [serverEvaluators],
  );

  return useMemo(
    () =>
      applyEvaluatorEdits(
        serverRows,
        addedEvaluators,
        editedEvaluators,
        deletedEvaluatorIds,
      ),
    [serverRows, addedEvaluators, editedEvaluators, deletedEvaluatorIds],
  );
}
