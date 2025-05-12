import React, { useCallback, useMemo, useRef, useState } from "react";
import { Play } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import useAppStore from "@/store/AppStore";
import { useSetPromptMap } from "@/store/PlaygroundStore";
import { PromptWithLatestVersion } from "@/types/prompts";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { generateDefaultPrompt } from "@/lib/playground";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";

type TryInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
};

const TryInPlaygroundButton: React.FC<TryInPlaygroundButtonProps> = ({
  prompt,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const setPromptMap = useSetPromptMap();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  const loadPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });

    newPrompt.messages = [
      generateDefaultLLMPromptMessage({
        content: prompt?.latest_version?.template ?? "",
        promptId: prompt?.id,
      }),
    ];

    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    navigate({
      to: "/$workspaceName/playground",
      params: {
        workspaceName,
      },
    });
  }, [
    calculateDefaultModel,
    calculateModelProvider,
    lastPickedModel,
    navigate,
    prompt,
    providerKeys,
    setPromptMap,
    workspaceName,
  ]);

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        disabled={!prompt || isPendingProviderKeys}
        onClick={() => {
          resetKeyRef.current = resetKeyRef.current + 1;
          setOpen(true);
        }}
      >
        <Play className="mr-2 size-3.5" />
        Try in Playground
      </Button>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={loadPlayground}
        title="Load prompt"
        description="Loading this prompt into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Load prompt"
      />
    </>
  );
};

export default TryInPlaygroundButton;
