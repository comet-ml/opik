import { useMemo } from "react";
import { DATASET_TYPE } from "@/types/datasets";
import {
  useDatasetType,
  useIsPromptOutputStale,
} from "@/store/PlaygroundStore";
import useTestSuitePromptResults from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useTestSuitePromptResults";

type BadgeColor = { bg: string; text: string };

const DEFAULT_COLOR: BadgeColor = { bg: "var(--click-blue)", text: "#ffffff" };
const WINNER_COLOR: BadgeColor = { bg: "var(--chart-green)", text: "#ffffff" };
const LOSER_COLOR: BadgeColor = { bg: "var(--chart-red)", text: "#ffffff" };

export default function usePromptBadgeColor(
  promptId: string,
  promptColor: BadgeColor,
): BadgeColor {
  const datasetType = useDatasetType();
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;
  const isOutputStale = useIsPromptOutputStale(promptId);
  const testSuiteResults = useTestSuitePromptResults();

  return useMemo(() => {
    if (!isTestSuite) return promptColor;

    const promptResult = testSuiteResults?.[promptId];
    if (promptResult?.passRate == null || isOutputStale) return DEFAULT_COLOR;

    return promptResult.isWinner ? WINNER_COLOR : LOSER_COLOR;
  }, [isTestSuite, promptColor, testSuiteResults, promptId, isOutputStale]);
}
