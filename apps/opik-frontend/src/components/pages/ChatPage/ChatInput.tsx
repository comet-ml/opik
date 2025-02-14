import React, { useCallback, useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import isFunction from "lodash/isFunction";
import TextareaAutosize from "react-textarea-autosize";
import { Play, Square } from "lucide-react";

import { ChatLLMessage, LLM_MESSAGE_ROLE, LLMChatType } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { OnChangeFn } from "@/types/shared";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import { useUpdateChat, useUpdateMessage } from "@/store/ChatStore";
import { getDefaultConfigByProvider } from "@/lib/playground";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import {
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";

const RUN_HOT_KEYS = ["⌘", "⏎"];

const ADDITIONAL_HEIGHT = 40;

type ChatInputProps = {
  workspaceName: string;
  providerKeys: PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  chat: LLMChatType;
  onHeightChange?: OnChangeFn<number>;
  abortControllerRef: React.MutableRefObject<AbortController | undefined>;
};

const ChatInput: React.FC<ChatInputProps> = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
  chat,
  onHeightChange,
  abortControllerRef,
}) => {
  const checkedIfModelIsValidRef = useRef(false);
  const {
    calculateModelProvider: providerResolver,
    calculateDefaultModel: modelResolver,
  } = useLLMProviderModelsData();

  const { toast } = useToast();

  const updateChat = useUpdateChat();
  const updateMessage = useUpdateMessage();
  const { model, provider, configs, value } = chat;

  const [isRunning, setIsRunning] = useState(false);
  const isDisabledButton = !value && !isRunning;

  const { getLocalIAProviderURL } = useLocalAIProviderData();
  const runStreaming = useCompletionProxyStreaming({
    workspaceName,
  });

  useEffect(() => {
    // on init, to check if a chat has a model from valid providers: (f.e., remove a provider after setting a model)
    if (!checkedIfModelIsValidRef.current && !isPendingProviderKeys) {
      checkedIfModelIsValidRef.current = true;

      const newModel = modelResolver(model, providerKeys);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updateChat({
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider),
        });
      }
    }
  }, [
    providerKeys,
    isPendingProviderKeys,
    providerResolver,
    modelResolver,
    model,
    updateChat,
  ]);

  const handleUpdateModel = useCallback(
    (newModel: PROVIDER_MODEL_TYPE, newProvider: PROVIDER_TYPE) => {
      updateChat({
        model: newModel,
        provider: newProvider,
        ...(newProvider !== provider && {
          configs: getDefaultConfigByProvider(newProvider),
        }),
      });
    },
    [provider, updateChat],
  );

  const handleAddProvider = useCallback(
    (provider: PROVIDER_TYPE) => {
      const newModel = modelResolver(model, providerKeys, provider);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updateChat({
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider),
        });
      }
    },
    [modelResolver, model, providerKeys, providerResolver, updateChat],
  );

  const handleUpdateConfig = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      updateChat({
        configs: {
          ...configs,
          ...newConfigs,
        } as LLMPromptConfigsType,
      });
    },
    [configs, updateChat],
  );

  const sendMessage = useCallback(async () => {
    const userMessage = generateDefaultLLMPromptMessage({
      role: LLM_MESSAGE_ROLE.user,
      content: chat.value,
    });

    const assistantMessage = generateDefaultLLMPromptMessage({
      role: LLM_MESSAGE_ROLE.assistant,
      content: "",
    }) as ChatLLMessage;

    const messages: ChatLLMessage[] = [...chat.messages, userMessage];

    updateChat({ messages: [...messages, assistantMessage] });
    updateMessage(assistantMessage.id, { isLoading: true });

    setIsRunning(true);
    abortControllerRef.current = new AbortController();

    try {
      const run = await runStreaming({
        url: getLocalIAProviderURL(chat.provider),
        model: chat.model,
        messages,
        configs: chat.configs,
        signal: abortControllerRef.current.signal,
        onAddChunk: (output) => {
          updateMessage(assistantMessage.id, { content: output });
        },
      });

      updateMessage(assistantMessage.id, { isLoading: false });

      const error = run.opikError || run.providerError || run.pythonProxyError;

      if (error) {
        throw new Error(error);
      }
    } catch (error) {
      const typedError = error as Error;

      toast({
        title: "Error",
        variant: "destructive",
        description: typedError.message,
      });
    } finally {
      abortControllerRef.current?.abort();
      abortControllerRef.current = undefined;
      setIsRunning(false);
    }
  }, [
    abortControllerRef,
    chat.configs,
    chat.messages,
    chat.model,
    chat.provider,
    chat.value,
    getLocalIAProviderURL,
    runStreaming,
    toast,
    updateChat,
    updateMessage,
  ]);

  const stopMessage = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = undefined;
    }
  }, [abortControllerRef]);

  const handleButtonClick = useCallback(() => {
    if (isRunning) {
      stopMessage();
    } else {
      sendMessage();
      updateChat({ value: "" });
    }
  }, [isRunning, stopMessage, sendMessage, updateChat]);

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
        event.preventDefault();
        event.stopPropagation();

        handleButtonClick();
      }
    },
    [handleButtonClick],
  );

  return (
    <div className="w-[700px] min-w-72 max-w-full">
      <div className="flex w-full items-center justify-end gap-1 pb-2">
        <div className="h-8 w-80">
          <PromptModelSelect
            value={model}
            onChange={handleUpdateModel}
            provider={provider}
            workspaceName={workspaceName}
            onAddProvider={handleAddProvider}
            hasError={!model}
          />
        </div>
        <PromptModelConfigs
          provider={provider}
          configs={configs}
          onChange={handleUpdateConfig}
        />
      </div>
      <div className="relative">
        <TextareaAutosize
          placeholder="How can i help you?"
          value={value}
          onChange={(event) => updateChat({ value: event.target.value })}
          onKeyDown={handleKeyDown}
          className={cn(TEXT_AREA_CLASSES, "min-h-8 leading-none pr-10")}
          minRows={3}
          maxRows={10}
          onHeightChange={(height) => {
            if (isFunction(onHeightChange)) {
              onHeightChange(height + ADDITIONAL_HEIGHT);
            }
          }}
        />
        <TooltipWrapper
          content={isRunning ? "Stop chat" : "Send message"}
          hotkeys={isDisabledButton ? undefined : RUN_HOT_KEYS}
        >
          <Button
            size="icon-sm"
            className="absolute bottom-2 right-2"
            onClick={handleButtonClick}
            disabled={isDisabledButton}
          >
            {isRunning ? (
              <Square className="size-4" />
            ) : (
              <Play className="size-4" />
            )}
          </Button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default ChatInput;
