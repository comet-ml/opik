import { FlaskConical, Sparkles, Split, LucideIcon } from "lucide-react";
import { EXPERIMENT_TYPE } from "@/types/datasets";

export type ExperimentIconConfig = {
  icon: LucideIcon;
  color: string;
  label: string;
};

export const EXPERIMENT_ICON_MAP: Record<string, ExperimentIconConfig> = {
  [EXPERIMENT_TYPE.REGULAR]: {
    icon: FlaskConical,
    color: "var(--color-burgundy)",
    label: "Experiment",
  },
  [EXPERIMENT_TYPE.TRIAL]: {
    icon: FlaskConical,
    color: "var(--color-burgundy)",
    label: "Trial",
  },
  [EXPERIMENT_TYPE.MINI_BATCH]: {
    icon: FlaskConical,
    color: "var(--color-burgundy)",
    label: "Mini-batch",
  },
  [EXPERIMENT_TYPE.LIVE]: {
    icon: FlaskConical,
    color: "var(--color-burgundy)",
    label: "Live",
  },
  [EXPERIMENT_TYPE.AB]: {
    icon: Split,
    color: "var(--color-blue)",
    label: "A/B Test",
  },
  [EXPERIMENT_TYPE.OPTIMIZER]: {
    icon: Sparkles,
    color: "var(--color-orange)",
    label: "Optimizer",
  },
};

export function getExperimentIconConfig(
  type?: string | null,
  assignedVariant?: string | null
): ExperimentIconConfig {
  // For AB tests, only show AB icon for non-control variants
  if (type === EXPERIMENT_TYPE.AB) {
    const isControlVariant =
      !assignedVariant ||
      assignedVariant === "default" ||
      assignedVariant === "A" ||
      assignedVariant === "control";

    if (isControlVariant) {
      return EXPERIMENT_ICON_MAP[EXPERIMENT_TYPE.LIVE];
    }
    return EXPERIMENT_ICON_MAP[EXPERIMENT_TYPE.AB];
  }

  if (type && type in EXPERIMENT_ICON_MAP) {
    return EXPERIMENT_ICON_MAP[type];
  }
  return EXPERIMENT_ICON_MAP[EXPERIMENT_TYPE.REGULAR];
}
