import { useMemo } from "react";
import { useIsPromptOutputStale } from "@/store/PlaygroundStore";
import useTestSuitePromptResults from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useTestSuitePromptResults";
import { PromptResult } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useTestSuitePromptResults";

export type PromptResultStatus = "default" | "winner" | "loser";

type PromptResultState = {
  status: PromptResultStatus;
  promptResult: PromptResult | undefined;
};

export default function usePromptResultStatus(
  promptId: string,
): PromptResultState {
  const isOutputStale = useIsPromptOutputStale(promptId);
  const testSuiteResults = useTestSuitePromptResults();

  return useMemo(() => {
    const promptResult = testSuiteResults?.[promptId];

    if (promptResult?.passRate == null || isOutputStale) {
      return { status: "default" as const, promptResult };
    }

    return {
      status: promptResult.isWinner ? ("winner" as const) : ("loser" as const),
      promptResult,
    };
  }, [testSuiteResults, promptId, isOutputStale]);
}
