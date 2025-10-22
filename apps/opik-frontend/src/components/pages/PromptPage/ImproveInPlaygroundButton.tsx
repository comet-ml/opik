import React, { useCallback, useMemo, useRef, useState } from "react";
import { Wand2 } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import useAppStore from "@/store/AppStore";
import { usePromptMap, useSetPromptMap } from "@/store/PlaygroundStore";
import { PromptWithLatestVersion } from "@/types/prompts";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { generateDefaultPrompt } from "@/lib/playground";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";

type ImproveInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
};

const ImproveInPlaygroundButton: React.FC<ImproveInPlaygroundButtonProps> = ({
  prompt,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const promptMap = usePromptMap();
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

  const isPlaygroundEmpty = useMemo(() => {
    const keys = Object.keys(promptMap);

    return (
      keys.length === 1 &&
      promptMap[keys[0]]?.messages?.length === 1 &&
      promptMap[keys[0]]?.messages[0]?.content === ""
    );
  }, [promptMap]);

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
        promptVersionId: prompt?.latest_version?.id,
        autoImprove: true,
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
      <TooltipWrapper content="Opens the prompt in the Playground for improvement">
        <Button
          variant="secondary"
          size="sm"
          disabled={!prompt || isPendingProviderKeys}
          onClick={() => {
            if (isPlaygroundEmpty) {
              loadPlayground();
            } else {
              resetKeyRef.current = resetKeyRef.current + 1;
              setOpen(true);
            }
          }}
        >
          <Wand2 className="mr-1.5 size-3.5" />
          Improve prompt
        </Button>
      </TooltipWrapper>
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

export default ImproveInPlaygroundButton;
