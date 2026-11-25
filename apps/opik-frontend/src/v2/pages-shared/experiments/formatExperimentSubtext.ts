import isNumber from "lodash/isNumber";

import { Experiment } from "@/types/datasets";
import { formatDate } from "@/lib/date";
import {
  formatExperimentPromptVersions,
  isTestSuiteExperiment,
} from "@/lib/experiments";
import { formatPassRatePercent } from "@/shared/DataTableCells/PassRateCell";

type FormatExperimentSubtextOptions = {
  // Name the test suite or dataset, e.g. "Test suite: geo v3". Used where the
  // source itself is what tells experiments apart (the mixed-source guard).
  withSourceName?: boolean;
};

const formatSource = (
  experiment: Experiment,
  withSourceName: boolean,
): string | undefined => {
  // Pre-migration experiments carry no evaluation_method; they are datasets.
  const label = isTestSuiteExperiment(experiment) ? "Test suite" : "Dataset";
  const versionName = experiment.dataset_version_summary?.version_name;

  if (!withSourceName) {
    return versionName && `${label} ${versionName}`;
  }

  const sourceName = experiment.dataset_name
    ? `${label}: ${experiment.dataset_name}`
    : `Deleted ${label.toLowerCase()}`;
  return versionName ? `${sourceName} ${versionName}` : sourceName;
};

export const formatExperimentSubtext = (
  experiment: Experiment,
  { withSourceName = false }: FormatExperimentSubtextOptions = {},
): string => {
  const prompts = formatExperimentPromptVersions(experiment);

  return [
    formatDate(experiment.created_at),
    formatSource(experiment, withSourceName),
    isNumber(experiment.pass_rate) &&
      `Pass rate: ${formatPassRatePercent(experiment.pass_rate)}`,
    prompts && `Prompt: ${prompts}`,
  ]
    .filter(Boolean)
    .join(" • ");
};
