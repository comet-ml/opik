import { useEffect } from "react";

import PlaygroundPrompt from "@/v2/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import {
  usePromptCount,
  usePromptIds,
  useSetPromptMap,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { generateDefaultPrompt } from "@/lib/playground";

interface PlaygroundPromptsProps {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
}: PlaygroundPromptsProps) => {
  const promptCount = usePromptCount();
  const promptIds = usePromptIds();
  const setPromptMap = useSetPromptMap();

  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  useEffect(() => {
    if (promptCount === 0 && !isPendingProviderKeys) {
      const newPrompt = generateDefaultPrompt({
        setupProviders: providerKeys,
        lastPickedModel,
        providerResolver: calculateModelProvider,
        modelResolver: calculateDefaultModel,
      });
      setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    }
  }, [
    promptCount,
    isPendingProviderKeys,
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
  ]);

  return (
    <div className="flex min-h-0 flex-1">
      {promptIds.map((promptId, idx) => (
        <PlaygroundPrompt
          workspaceName={workspaceName}
          promptId={promptId}
          index={idx}
          key={promptId}
          providerKeys={providerKeys}
          isPendingProviderKeys={isPendingProviderKeys}
          providerResolver={calculateModelProvider}
          modelResolver={calculateDefaultModel}
        />
      ))}
    </div>
  );
};

export default PlaygroundPrompts;
