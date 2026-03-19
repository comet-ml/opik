import {
  EVALUATION_METHOD,
  Experiment,
  EvalSuiteExperiment,
} from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";

export function isEvalSuiteExperiment(
  experiment: Experiment | null | undefined,
): experiment is EvalSuiteExperiment {
  return experiment?.evaluation_method === EVALUATION_METHOD.EVALUATION_SUITE;
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
