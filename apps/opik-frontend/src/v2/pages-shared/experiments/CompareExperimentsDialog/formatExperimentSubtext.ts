import isNumber from "lodash/isNumber";

import { Experiment } from "@/types/datasets";
import { formatDate } from "@/lib/date";
import {
  formatExperimentPromptVersions,
  isTestSuiteExperiment,
} from "@/lib/experiments";
import { formatPassRatePercent } from "@/shared/DataTableCells/PassRateCell";

export const formatExperimentSubtext = (experiment: Experiment): string => {
  const versionName = experiment.dataset_version_summary?.version_name;
  const prompts = formatExperimentPromptVersions(experiment);

  return [
    formatDate(experiment.created_at),
    versionName &&
      `${isTestSuiteExperiment(experiment) ? "Test suite" : "Dataset"} ${versionName}`,
    isNumber(experiment.pass_rate) &&
      `Pass rate: ${formatPassRatePercent(experiment.pass_rate)}`,
    prompts && `Prompt: ${prompts}`,
  ]
    .filter(Boolean)
    .join(" • ");
};
