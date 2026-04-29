import {
  EVALUATION_METHOD,
  Experiment,
  EXPERIMENT_STATUS,
  TestSuiteExperiment,
} from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";

export const isExperimentTerminal = (
  status: EXPERIMENT_STATUS | undefined | null,
): boolean =>
  status === EXPERIMENT_STATUS.COMPLETED ||
  status === EXPERIMENT_STATUS.CANCELLED;

export function isTestSuiteExperiment(
  experiment: Experiment | null | undefined,
): experiment is TestSuiteExperiment {
  return experiment?.evaluation_method === EVALUATION_METHOD.TEST_SUITE;
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
