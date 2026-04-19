import { useMemo } from "react";
import { DATASET_TYPE } from "@/types/datasets";
import { useDatasetType } from "@/store/PlaygroundStore";
import usePromptResultStatus from "@/v2/pages/PlaygroundPage/usePromptResultStatus";

type BadgeColor = { bg: string; text: string };

const DEFAULT_COLOR: BadgeColor = { bg: "var(--click-blue)", text: "#ffffff" };
const WINNER_COLOR: BadgeColor = { bg: "var(--chart-green)", text: "#ffffff" };
const LOSER_COLOR: BadgeColor = { bg: "var(--chart-red)", text: "#ffffff" };

const STATUS_TO_BADGE: Record<string, BadgeColor> = {
  default: DEFAULT_COLOR,
  winner: WINNER_COLOR,
  loser: LOSER_COLOR,
};

export default function usePromptBadgeColor(
  promptId: string,
  promptColor: BadgeColor,
): BadgeColor {
  const datasetType = useDatasetType();
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;
  const { status } = usePromptResultStatus(promptId);

  return useMemo(() => {
    if (!isTestSuite) return promptColor;
    return STATUS_TO_BADGE[status];
  }, [isTestSuite, promptColor, status]);
}
