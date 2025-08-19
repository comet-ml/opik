import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { MoreHorizontal, Trash } from "lucide-react";
import { useToast } from "@/components/ui/use-toast";

import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import TraceChatInput from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/TraceChatInput";
import TraceChatMessage from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/TraceChatMessage";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import useTraceAnalyzerHistory from "@/api/ai-assistant/useTraceAnalyzerHistory";
import useTraceAnalyzerRunStreaming from "@/api/ai-assistant/useTraceAnalyzerRunStreaming";
import useTraceAnalyzerDeleteSession from "@/api/ai-assistant/useTraceAnalyzerDeleteSession";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Tag } from "@/components/ui/tag";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/components/shared/Loader/Loader";
import {
  TraceAnalyzerLLMMessage,
  TraceLLMChatType,
} from "@/types/ai-assistant";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

interface TraceAIViewerProps {
  traceId: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
}

const TraceAIViewer: React.FC<TraceAIViewerProps> = ({
  traceId,
  activeSection,
  setActiveSection,
}) => {
  const [chat, setChat] = useState<TraceLLMChatType>({
    value: "",
    messages: [],
  });
  const [isRunning, setIsRunning] = useState(false);
  const abortControllerRef = useRef<AbortController>();
  const { toast } = useToast();

  const startStreaming = useCallback(() => {
    abortControllerRef.current = new AbortController();
    setIsRunning(true);
    return abortControllerRef.current;
  }, []);

  const stopStreaming = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = undefined;
    }
    setIsRunning(false);
  }, []);

  const { data: historyData, isPending: isHistoryPending } =
    useTraceAnalyzerHistory({ traceId });

  const { scrollContainerRef } = useChatScroll({
    messages: chat.messages,
    isStreaming: isRunning,
  });

  useEffect(() => {
    setChat({ value: "", messages: [] });
    stopStreaming();
  }, [traceId, stopStreaming]);

  useEffect(() => {
    const historyMessages = historyData?.content || [];

    if (
      chat.messages.length === 0 &&
      historyMessages.length > 0 &&
      !isRunning
    ) {
      setChat((prev) => ({ ...prev, messages: historyMessages }));
    }
  }, [historyData?.content, chat.messages.length, isRunning]);

  useEffect(() => {
    const controllerAtMount = abortControllerRef.current;
    return () => {
      controllerAtMount?.abort();
    };
  }, []);

  const noMessages = useMemo(() => !chat.messages.length, [chat.messages]);

  const predefinedPrompts = useMemo(
    () => [
      "Analyze this trace and identify any anomalies",
      "Explain the cause of slowness in this trace and recommend improvements",
      "Diagnose the reason for this trace failure and suggest potential fixes",
      "Give me a summary of this trace",
    ],
    [],
  );

  const runStreaming = useTraceAnalyzerRunStreaming({
    traceId,
  });

  const { mutate: deleteMutate } = useTraceAnalyzerDeleteSession();
  const [showClearConfirm, setShowClearConfirm] = useState(false);

  const updateMessage = useCallback(
    (messageId: string, updates: Partial<TraceAnalyzerLLMMessage>) => {
      setChat((prev) => ({
        ...prev,
        messages: prev.messages.map((m) =>
          m.id === messageId ? { ...m, ...updates } : m,
        ),
      }));
    },
    [],
  );

  const handleDeleteSession = useCallback(() => {
    stopStreaming();
    deleteMutate({ traceId });
    setChat((prev) => ({ ...prev, value: "", messages: [] }));
  }, [traceId, deleteMutate, stopStreaming]);

  const sendMessage = useCallback(
    async (content: string) => {
      const userMessage = generateDefaultLLMPromptMessage({
        role: LLM_MESSAGE_ROLE.user,
        content,
      }) as TraceAnalyzerLLMMessage;

      const assistantMessage = generateDefaultLLMPromptMessage({
        role: LLM_MESSAGE_ROLE.assistant,
        content: "",
      }) as TraceAnalyzerLLMMessage;

      setChat((prev) => ({
        ...prev,
        messages: [
          ...prev.messages,
          userMessage,
          { ...assistantMessage, isLoading: true },
        ],
      }));

      const abortController = startStreaming();

      try {
        const { error } = await runStreaming({
          message: content,
          signal: abortController.signal,
          onAddChunk: (output) => {
            updateMessage(assistantMessage.id, { content: output });
          },
        });

        updateMessage(assistantMessage.id, { isLoading: false });

        if (error) {
          throw new Error(error);
        }
      } catch (error) {
        const typedError = error as Error;
        updateMessage(assistantMessage.id, {
          content: typedError.message,
          isLoading: false,
          isError: true,
        });

        toast({
          title: "Error",
          variant: "destructive",
          description: typedError.message,
        });
      } finally {
        stopStreaming();
      }
    },
    [runStreaming, toast, updateMessage, stopStreaming, startStreaming],
  );

  const handleButtonClick = useCallback(() => {
    if (isRunning) {
      stopStreaming();
    } else if (chat.value.trim()) {
      sendMessage(chat.value);
      setChat((prev) => ({ ...prev, value: "" }));
    }
  }, [isRunning, stopStreaming, chat.value, sendMessage]);

  const renderNoMessage = () => {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 py-20">
        <div className="comet-title-m mb-10 text-center">
          Ready to dig into your trace? Start with the sample query or enter
          your own below
        </div>
        {predefinedPrompts.map((prompt) => (
          <Button
            key={prompt}
            variant="outline"
            className="h-auto justify-start whitespace-normal py-3"
            onClick={() => sendMessage(prompt)}
            aria-label={`Use prompt: ${prompt}`}
          >
            {prompt}
          </Button>
        ))}
      </div>
    );
  };

  return (
    <DetailsActionSectionLayout
      title="Inspect trace"
      closeTooltipContent="Close inspect trace"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
      tag={<Tag variant="green">Beta</Tag>}
      button={
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon-sm">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem
              onClick={() => setShowClearConfirm(true)}
              disabled={noMessages}
            >
              <Trash className="mr-2 size-4" />
              Clear conversation
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      }
    >
      <div className="relative h-full overflow-hidden">
        <div className="max-h-full overflow-y-auto" ref={scrollContainerRef}>
          <div className="min-h-[70vh] px-6 py-4">
            {isHistoryPending ? (
              <Loader />
            ) : noMessages ? (
              renderNoMessage()
            ) : (
              <div className="flex size-full justify-center">
                <div className="flex w-full flex-col gap-2">
                  {chat.messages.map((m) => (
                    <TraceChatMessage key={m.id} message={m} />
                  ))}
                </div>
              </div>
            )}
          </div>
          <div className="sticky inset-x-0 bottom-0 z-10 border-t bg-white px-6 py-3">
            <TraceChatInput
              chat={chat}
              isRunning={isRunning}
              onUpdateChat={(changes) =>
                setChat((prev) => ({ ...prev, ...changes }))
              }
              onButtonClick={handleButtonClick}
            />
          </div>
        </div>
      </div>
      <ConfirmDialog
        open={showClearConfirm}
        setOpen={setShowClearConfirm}
        onConfirm={handleDeleteSession}
        title="Clear conversation?"
        description="This will remove the current AI assistant session for this trace. You cannot undo this action."
        confirmText="Clear"
        confirmButtonVariant="destructive"
      />
    </DetailsActionSectionLayout>
  );
};

export default TraceAIViewer;
