import { Experiment } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";

export const getExperimentChangeDescription = (
  experiment: Pick<Experiment, "prompt_versions" | "prompt_version">,
): string | undefined => {
  return (
    experiment.prompt_versions?.[0]?.change_description ??
    experiment.prompt_version?.change_description
  );
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
