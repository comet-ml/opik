import {
  EVALUATION_METHOD,
  Experiment,
  ExperimentPromptVersion,
  EXPERIMENT_STATUS,
  TestSuiteExperiment,
} from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";

/**
 * Human-readable label for a prompt version linked to an experiment: the
 * prompt name plus its version (e.g. "My Prompt (v3)"). Prefers the sequential
 * version number, falls back to the commit hash when it's unavailable, and
 * omits the parenthetical entirely when neither is present (OPIK-6838).
 *
 * Single source of truth so the experiments table, the single-experiment
 * Configuration tab, and the dashboard leaderboard widget stay consistent.
 */
export const formatPromptVersionLabel = (
  promptVersion: Pick<
    ExperimentPromptVersion,
    "prompt_name" | "version_number" | "commit"
  >,
): string => {
  const version = promptVersion.version_number ?? promptVersion.commit;
  return version
    ? `${promptVersion.prompt_name} (${version})`
    : promptVersion.prompt_name;
};

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

export const EXPERIMENT_TAB = {
  items: "items",
  insights: "insights",
  config: "config",
  scores: "scores",
  logs: "logs",
} as const;

export type ExperimentTabId =
  (typeof EXPERIMENT_TAB)[keyof typeof EXPERIMENT_TAB];

/**
 * Which tabs the experiment page exposes, in display order.
 *
 * Insights and feedback scores don't apply to test-suite experiments; feedback scores also need at
 * least one loaded experiment. Logs is always available — every experiment run produces traces, and
 * comparisons show the traces of all compared experiments (OPIK-6739).
 */
export const getAvailableExperimentTabs = (
  experiments: Experiment[],
): ExperimentTabId[] => {
  const isTestSuite = isTestSuiteExperiment(experiments[0]);

  return [
    EXPERIMENT_TAB.items,
    ...(!isTestSuite ? [EXPERIMENT_TAB.insights] : []),
    EXPERIMENT_TAB.config,
    ...(experiments.length > 0 && !isTestSuite ? [EXPERIMENT_TAB.scores] : []),
    EXPERIMENT_TAB.logs,
  ];
};

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
