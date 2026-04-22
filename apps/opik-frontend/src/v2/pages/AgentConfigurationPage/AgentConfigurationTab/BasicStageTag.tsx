import React from "react";
import { Code, FlaskConical, LucideIcon, Rocket } from "lucide-react";
import capitalize from "lodash/capitalize";

import { AgentConfigurationBasicStage } from "@/utils/agent-configurations";

type BasicStageTagSize = "xs" | "sm";

type BasicStageTagProps = {
  size?: BasicStageTagSize;
  value: string;
};

type StageConfig = {
  icon: LucideIcon;
  bg: string;
  text: string;
};

const STAGE_CONFIGS: Record<string, StageConfig> = {
  [AgentConfigurationBasicStage.DEV]: {
    icon: Code,
    bg: "bg-[var(--stage-dev-bg)]",
    text: "text-[var(--stage-dev-text)]",
  },
  [AgentConfigurationBasicStage.STAGING]: {
    icon: FlaskConical,
    bg: "bg-[var(--stage-staging-bg)]",
    text: "text-[var(--stage-staging-text)]",
  },
  [AgentConfigurationBasicStage.PROD]: {
    icon: Rocket,
    bg: "bg-[var(--tag-lime-bg)]",
    text: "text-[var(--tag-lime-text)]",
  },
};

const SIZE_CLASSES: Record<BasicStageTagSize, string> = {
  xs: "comet-body-xs-accented h-4 gap-1 rounded px-1 leading-4",
  sm: "comet-body-s-accented h-6 gap-1.5 rounded-md px-1.5 leading-6",
};

const ICON_SIZE: Record<BasicStageTagSize, string> = {
  xs: "size-3",
  sm: "size-3.5",
};

const BasicStageTag: React.FC<BasicStageTagProps> = ({
  size = "sm",
  value,
}) => {
  const config = STAGE_CONFIGS[value];
  if (!config) return null;

  const Icon = config.icon;

  return (
    <div
      className={`inline-flex items-center ${config.bg} ${config.text} ${SIZE_CLASSES[size]}`}
    >
      <Icon className={`shrink-0 ${ICON_SIZE[size]}`} />
      {capitalize(value)}
    </div>
  );
};

export default BasicStageTag;
