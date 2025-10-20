import React, { useCallback, useMemo, useRef, useState } from "react";
import { Play } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { Span, SPAN_TYPE } from "@/types/traces";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useAppStore from "@/store/AppStore";
import { usePromptMap, useSetPromptMap } from "@/store/PlaygroundStore";
import { generateDefaultPrompt } from "@/lib/playground";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import {
  hasValidMessagesFormat,
  convertSpanMessagesToLLMMessages,
} from "@/lib/playgroundHelpers";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";
import { isObjectSpan } from "@/lib/traces";

type OpenInPlaygroundButtonProps = {
  data: Span;
  layoutSize: ButtonLayoutSize;
};

const OpenInPlaygroundButton: React.FC<OpenInPlaygroundButtonProps> = ({
  data,
  layoutSize,
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

  // Check if this is an LLM span with valid messages format
  const canOpenInPlayground = useMemo(() => {
    return (
      isObjectSpan(data) &&
      data.type === SPAN_TYPE.llm &&
      hasValidMessagesFormat(data)
    );
  }, [data]);

  const loadPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });

    // Convert span messages to LLMMessage format
    newPrompt.messages = convertSpanMessagesToLLMMessages(data);

    // If the span has model information, use it
    if (data.model) {
      // Try to match the model from the span
      const matchedModel = calculateDefaultModel(data.model, providerKeys);
      if (matchedModel) {
        newPrompt.model = matchedModel;
        newPrompt.provider = calculateModelProvider(matchedModel);
      }
    }

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
    data,
    providerKeys,
    setPromptMap,
    workspaceName,
  ]);

  if (!canOpenInPlayground) {
    return null;
  }

  const showFullLabel = layoutSize === ButtonLayoutSize.Large;

  return (
    <>
      <TooltipWrapper content="Open in Playground">
        <Button
          variant="outline"
          size={showFullLabel ? "sm" : "icon-sm"}
          disabled={isPendingProviderKeys}
          onClick={() => {
            if (isPlaygroundEmpty) {
              loadPlayground();
            } else {
              resetKeyRef.current = resetKeyRef.current + 1;
              setOpen(true);
            }
          }}
        >
          <Play className={showFullLabel ? "mr-1.5 size-3.5" : "size-3.5"} />
          {showFullLabel && <span>Playground</span>}
        </Button>
      </TooltipWrapper>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={loadPlayground}
        title="Open in Playground"
        description="Loading this span into the Playground will replace any unsaved changes. This action cannot be undone."
        confirmText="Open in Playground"
      />
    </>
  );
};

export default OpenInPlaygroundButton;