import React, { useEffect, useMemo, useState } from "react";
import { Loader } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import { useNavigate } from "@tanstack/react-router";

import { PLAYGROUND_SELECTED_DATASET_KEY } from "@/constants/llm";
import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ResizablePromptContainer from "@/components/pages/PlaygroundPage/ResizablePromptContainer";
import PlaygroundEvaluationBar from "@/components/pages/PlaygroundPage/PlaygroundEvaluationBar/PlaygroundEvaluationBar";
import PlaygroundEvaluationResults from "@/components/pages/PlaygroundPage/PlaygroundEvaluationResults/PlaygroundEvaluationResults";
import usePlaygroundEvaluation, {
  PlaygroundEvaluationResponse,
} from "@/api/playground/usePlaygroundEvaluation";
import { usePromptMap } from "@/store/PlaygroundStore";

const LEGACY_PLAYGROUND_PROMPTS_KEY = "playground-prompts-state";

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const promptMap = usePromptMap();

  // Evaluation state
  const [selectedEvaluators, setSelectedEvaluators] = useState<string[]>([]);
  const [evaluationResults, setEvaluationResults] =
    useState<PlaygroundEvaluationResponse | null>(null);

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  const [datasetId, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  // Playground evaluation mutation
  const playgroundEvaluationMutation = usePlaygroundEvaluation({
    workspaceName,
  });

  // @todo: remove later
  // this field is not used anymore
  useEffect(() => {
    localStorage.removeItem(LEGACY_PLAYGROUND_PROMPTS_KEY);
  }, []);

  const handleStartExperiment = async () => {
    if (!datasetId || selectedEvaluators.length === 0) return;

    // Get the first prompt (for now, assume single prompt evaluation)
    const promptIds = Object.keys(promptMap);
    if (promptIds.length === 0) return;

    const firstPrompt = promptMap[promptIds[0]];
    if (!firstPrompt) return;

    try {
      const result = await playgroundEvaluationMutation.mutateAsync({
        datasetId,
        messages: firstPrompt.messages,
        model: firstPrompt.model,
        modelConfig: firstPrompt.configs as Record<string, unknown>,
        evaluationMetrics: selectedEvaluators,
      });

      setEvaluationResults(result);
    } catch (error) {
      console.error("Evaluation failed:", error);
    }
  };

  const handleViewFullAnalysis = () => {
    if (evaluationResults?.experimentId) {
      navigate({
        to: `/${workspaceName}/experiments/${evaluationResults.experimentId}`,
      });
    }
  };

  const handleRunAnother = () => {
    setEvaluationResults(null);
    setSelectedEvaluators([]);
  };

  if (isPendingProviderKeys) {
    return <Loader />;
  }

  return (
    <div
      className="flex h-full w-fit min-w-full flex-col pt-6"
      style={
        {
          "--min-prompt-width": "560px",
          "--item-gap": "1.5rem",
        } as React.CSSProperties
      }
    >
      <ResizablePromptContainer
        workspaceName={workspaceName}
        providerKeys={providerKeys}
        isPendingProviderKeys={isPendingProviderKeys}
      />

      {/* Evaluation Bar - LangSmith style */}
      <PlaygroundEvaluationBar
        datasetId={datasetId}
        onDatasetChange={setDatasetId}
        selectedEvaluators={selectedEvaluators}
        onEvaluatorsChange={setSelectedEvaluators}
        onStartExperiment={handleStartExperiment}
        isRunning={playgroundEvaluationMutation.isPending}
        workspaceName={workspaceName}
      />

      {/* Evaluation Results (shown after completion) */}
      {evaluationResults && (
        <div className="px-6">
          <PlaygroundEvaluationResults
            results={evaluationResults}
            onViewFullAnalysis={handleViewFullAnalysis}
            onRunAnother={handleRunAnother}
          />
        </div>
      )}

      {/* Original Playground Outputs */}
      <div className="flex">
        <PlaygroundOutputs
          datasetId={datasetId}
          onChangeDatasetId={setDatasetId}
          workspaceName={workspaceName}
        />
      </div>
    </div>
  );
};

export default PlaygroundPage;
