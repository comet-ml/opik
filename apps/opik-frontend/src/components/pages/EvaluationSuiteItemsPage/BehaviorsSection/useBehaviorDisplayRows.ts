import { useMemo } from "react";
import {
  MetricType,
  MetricConfig,
  StringMatchConfig,
  ThresholdConfig,
  LLMJudgeConfig,
  BehaviorDisplayRow,
  DatasetEvaluator,
  DatasetItemEvaluator,
} from "@/types/evaluation-suites";

export function getConfigTooltip(
  metricType: MetricType,
  config: MetricConfig,
): string {
  switch (metricType) {
    case MetricType.CONTAINS:
    case MetricType.EQUALS: {
      const c = config as StringMatchConfig;
      const caseSuffix = c.case_sensitive
        ? "case sensitive"
        : "case insensitive";
      return `"${c.value}", ${caseSuffix}`;
    }
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION:
      return `threshold: ${(config as ThresholdConfig).threshold}`;
    case MetricType.LLM_AS_JUDGE:
      return (config as LLMJudgeConfig).assertions?.[0] ?? "";
    default:
      return "";
  }
}

export function getRowNameClassName(row: BehaviorDisplayRow): string {
  if (row.isNew) return "text-orange-600";
  if (row.isEdited) return "text-blue-600";
  return "";
}

export function expandEvaluatorsToRows(
  evaluators: (DatasetEvaluator | DatasetItemEvaluator)[],
): BehaviorDisplayRow[] {
  const rows: BehaviorDisplayRow[] = [];

  for (const evaluator of evaluators) {
    if (
      evaluator.metric_type === MetricType.LLM_AS_JUDGE &&
      (evaluator.metric_config as LLMJudgeConfig).assertions?.length > 1
    ) {
      const assertions = (evaluator.metric_config as LLMJudgeConfig)
        .assertions;
      for (let i = 0; i < assertions.length; i++) {
        rows.push({
          id: `${evaluator.id}_${i}`,
          evaluatorId: evaluator.id,
          name: evaluator.name + (assertions.length > 1 ? ` (${i + 1})` : ""),
          metric_type: evaluator.metric_type,
          metric_config: { assertions: [assertions[i]] },
        });
      }
    } else {
      rows.push({
        id: evaluator.id,
        evaluatorId: evaluator.id,
        name: evaluator.name,
        metric_type: evaluator.metric_type,
        metric_config: evaluator.metric_config,
      });
    }
  }

  return rows;
}

export function useBehaviorDisplayRows(
  serverEvaluators: (DatasetEvaluator | DatasetItemEvaluator)[],
  addedBehaviors: Map<string, BehaviorDisplayRow>,
  editedBehaviors: Map<string, Partial<BehaviorDisplayRow>>,
  deletedBehaviorIds: Set<string>,
): BehaviorDisplayRow[] {
  const serverRows = useMemo(
    () => expandEvaluatorsToRows(serverEvaluators),
    [serverEvaluators],
  );

  return useMemo(() => {
    const rows: BehaviorDisplayRow[] = [];

    for (const row of serverRows) {
      if (deletedBehaviorIds.has(row.id)) continue;
      const edits = editedBehaviors.get(row.id);
      if (edits) {
        rows.push({ ...row, ...edits, isEdited: true });
      } else {
        rows.push(row);
      }
    }

    for (const added of addedBehaviors.values()) {
      rows.push(added);
    }

    return rows;
  }, [serverRows, addedBehaviors, editedBehaviors, deletedBehaviorIds]);
}
