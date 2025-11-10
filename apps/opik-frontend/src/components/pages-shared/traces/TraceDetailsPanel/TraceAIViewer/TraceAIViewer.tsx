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
      !isRunning &&
      !isHistoryPending
    ) {
      setChat((prev) => ({ ...prev, messages: historyMessages }));
    }
  }, [historyData?.content, chat.messages.length, isRunning, isHistoryPending]);

  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, [traceId]);

  const noMessages = useMemo(() => !chat.messages.length, [chat.messages]);

  const predefinedPrompts = useMemo(
    () => [
      "Give me a summary of this trace",
      "What went wrong or looks suspicious here?",
      "Where did errors or unexpected behavior occur?",
      "How can I reduce costs?",
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
    deleteMutate(
      { traceId },
      {
        onSuccess: () => {
          setChat((prev) => ({ ...prev, value: "", messages: [] }));
        },
      },
    );
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
            updateMessage(assistantMessage.id, output);
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

  const renderEmptyState = () => {
    return (
      <div className="flex min-h-full flex-col items-center justify-center gap-3 py-2">
        <div className="comet-title-m text-center text-foreground">
          Debug your trace with OpikAssist
        </div>
        <div className="comet-body-s mb-8 text-center text-muted-slate">
          Get AI-powered help spotting issues, understanding behavior, or
          debugging problems. Start with a sample question or ask your own.
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
      title="OpikAssist"
      closeTooltipContent="Close OpikAssist"
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
      <div className="flex h-full flex-col overflow-hidden py-4">
        {/* Central content area - takes remaining space */}
        <div className="flex flex-1 flex-col overflow-hidden border-t">
          <div className="flex-1 overflow-y-auto px-6" ref={scrollContainerRef}>
            {isHistoryPending ? (
              <div className="flex h-full items-center justify-center">
                <Loader />
              </div>
            ) : noMessages ? (
              renderEmptyState()
            ) : (
              <div className="flex w-full flex-col gap-2 py-4">
                {chat.messages.map((m) => (
                  <TraceChatMessage key={m.id} message={m} />
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Chat input area - up to 50% of container height */}
        <div className="flex max-h-[50%] min-h-0 flex-col border-t bg-background">
          <div className="flex-1 overflow-y-auto px-6 py-3">
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
