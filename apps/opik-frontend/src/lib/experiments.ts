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
