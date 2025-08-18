import React, { useCallback, useState } from "react";
import { cn } from "@/lib/utils";
import TextareaAutosize from "react-textarea-autosize";
import { Play, Square } from "lucide-react";

import { ChatLLMessage, LLM_MESSAGE_ROLE, LLMChatType } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { OnChangeFn } from "@/types/shared";
import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  RunStreamingArgs,
  RunStreamingReturn,
} from "@/api/playground/useCompletionProxyStreaming";

const RUN_HOT_KEYS = ["⌘", "⏎"];

type TraceChatInputProps = {
  workspaceName: string;
  chat: LLMChatType;
  onHeightChange?: OnChangeFn<number>;
  abortControllerRef: React.MutableRefObject<AbortController | undefined>;
  withTitle?: boolean;
  runStreamingFn: (args: RunStreamingArgs) => Promise<RunStreamingReturn>;
  onUpdateChat: (changes: Partial<LLMChatType>) => void;
  onUpdateMessage: (messageId: string, changes: Partial<ChatLLMessage>) => void;
};

const TraceChatInput: React.FC<TraceChatInputProps> = ({
  chat,
  onHeightChange,
  abortControllerRef,
  withTitle = false,
  runStreamingFn,
  onUpdateChat,
  onUpdateMessage,
}) => {
  const { toast } = useToast();

  const { value } = chat;

  const [isRunning, setIsRunning] = useState(false);
  const isDisabledButton = !value && !isRunning;

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

    onUpdateChat({ messages: [...messages, assistantMessage] });
    onUpdateMessage(assistantMessage.id, { isLoading: true });

    setIsRunning(true);
    abortControllerRef.current = new AbortController();

    try {
      const run = await runStreamingFn({
        // model/configs are not used by Trace Analyzer, but keep signature
        model: chat.model,
        messages,
        configs: chat.configs,
        signal: abortControllerRef.current.signal,
        onAddChunk: (output) => {
          onUpdateMessage(assistantMessage.id, { content: output });
        },
      });

      onUpdateMessage(assistantMessage.id, { isLoading: false });

      const error = run.opikError || run.providerError || run.pythonProxyError;

      if (error) {
        throw new Error(error);
      }
    } catch (error) {
      const typedError = error as Error;
      onUpdateMessage(assistantMessage.id, { content: typedError.message });

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
    chat.value,
    onUpdateChat,
    onUpdateMessage,
    runStreamingFn,
    toast,
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
      onUpdateChat({ value: "" });
    }
  }, [isRunning, stopMessage, sendMessage, onUpdateChat]);

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
    <div className="w-[768px] min-w-72 max-w-full">
      {withTitle && (
        <div className="pb-2">
          <h1 className="comet-title-xl mb-6 text-center">
            How can I help you?
          </h1>
        </div>
      )}
      <div className="relative">
        <TextareaAutosize
          placeholder="Type your message"
          value={value}
          onChange={(event) => onUpdateChat({ value: event.target.value })}
          onKeyDown={handleKeyDown}
          className={cn(TEXT_AREA_CLASSES, "min-h-8 leading-none pr-10")}
          minRows={5}
          maxRows={10}
          onHeightChange={onHeightChange}
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
            {isRunning ? <Square /> : <Play />}
          </Button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default TraceChatInput;
