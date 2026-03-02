import { EXPERIMENT_TYPE } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";

const EVAL_SUITE_EXPERIMENT_TYPES: ReadonlySet<EXPERIMENT_TYPE> = new Set([
  EXPERIMENT_TYPE.TRIAL,
  EXPERIMENT_TYPE.MINI_BATCH,
]);

export function isEvalSuiteExperimentType(
  type: EXPERIMENT_TYPE | undefined,
): boolean {
  return type !== undefined && EVAL_SUITE_EXPERIMENT_TYPES.has(type);
}

export const calculateLineHeight = (
  height: ROW_HEIGHT,
  lineCount: number = 1,
) => {
  const lineHeight = 32;
  const lineHeightMap: Record<ROW_HEIGHT, number> = {
    [ROW_HEIGHT.small]: 1,
    [ROW_HEIGHT.medium]: 4,
    [ROW_HEIGHT.large]: 12,
  };

  return {
    height: `${lineCount * lineHeightMap[height] * lineHeight}px`,
  };
};
