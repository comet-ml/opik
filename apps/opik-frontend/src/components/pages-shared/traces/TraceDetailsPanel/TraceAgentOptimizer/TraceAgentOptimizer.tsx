import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { MoreHorizontal, RefreshCw } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useToast } from "@/components/ui/use-toast";

import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
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
import useAgentOptimizerHistory, {
  AGENT_OPTIMIZER_HISTORY_KEY,
} from "@/api/agent-optimizer/useAgentOptimizerHistory";
import useAgentOptimizerRunStreaming from "@/api/agent-optimizer/useAgentOptimizerRunStreaming";
import useAgentOptimizerDeleteSession from "@/api/agent-optimizer/useAgentOptimizerDeleteSession";
import {
  AgentOptimizerMessage,
  OptimizerChatType,
  UserResponse,
} from "@/types/agent-optimizer";
import OptimizerChatMessage from "./OptimizerChatMessage";
import OptimizerChatInput from "./OptimizerChatInput";

interface TraceAgentOptimizerProps {
  traceId: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
}

const TraceAgentOptimizer: React.FC<TraceAgentOptimizerProps> = ({
  traceId,
  activeSection,
  setActiveSection,
}) => {
  const [chat, setChat] = useState<OptimizerChatType>({
    value: "",
    messages: [],
  });
  const [isRunning, setIsRunning] = useState(false);
  const [hasAutoStarted, setHasAutoStarted] = useState(false);
  const abortControllerRef = useRef<AbortController>();
  const { toast } = useToast();
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const queryClient = useQueryClient();

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
    useAgentOptimizerHistory({ traceId });

  useEffect(() => {
    setChat({ value: "", messages: [] });
    setHasAutoStarted(false);
    stopStreaming();
  }, [traceId, stopStreaming]);

  useEffect(() => {
    const historyMessages = historyData?.content || [];
    const debugInfo = {
      chatMessagesLength: chat.messages.length,
      historyMessagesLength: historyMessages.length,
      isRunning,
      isHistoryPending,
      hasAutoStarted,
      willLoadHistory:
        chat.messages.length === 0 &&
        historyMessages.length > 0 &&
        !isRunning &&
        !isHistoryPending &&
        !hasAutoStarted,
    };
    console.log("[TraceAgentOptimizer] History load useEffect:", debugInfo);

    if (
      chat.messages.length === 0 &&
      historyMessages.length > 0 &&
      !isRunning &&
      !isHistoryPending &&
      !hasAutoStarted // Don't load history if we've already started a fresh session
    ) {
      console.log(
        "[TraceAgentOptimizer] Loading history messages into chat. Count:",
        historyMessages.length,
      );
      setChat((prev) => ({ ...prev, messages: historyMessages }));
    }
  }, [
    historyData?.content,
    chat.messages.length,
    isRunning,
    isHistoryPending,
    hasAutoStarted,
  ]);

  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, [traceId]);

  // Auto-scroll when new messages are added
  useEffect(() => {
    if (scrollContainerRef.current && chat.messages.length > 0) {
      // Use requestAnimationFrame to ensure DOM has updated
      requestAnimationFrame(() => {
        if (scrollContainerRef.current) {
          scrollContainerRef.current.scrollTop =
            scrollContainerRef.current.scrollHeight;
        }
      });
    }
  }, [chat.messages]);

  const noMessages = useMemo(() => !chat.messages.length, [chat.messages]);

  const runStreaming = useAgentOptimizerRunStreaming({ traceId });

  const { mutate: deleteMutate } = useAgentOptimizerDeleteSession();
  const [showStartFreshConfirm, setShowStartFreshConfirm] = useState(false);

  const addOrUpdateMessage = useCallback(
    (output: Partial<AgentOptimizerMessage>) => {
      if (!output.id) {
        console.warn("[TraceAgentOptimizer] Chunk has no ID, skipping");
        return;
      }

      console.log(
        "[TraceAgentOptimizer] Received chunk - ID:",
        output.id,
        "Type:",
        output.type,
        "Has traceData:",
        !!output.traceData,
      );

      setChat((prev) => {
        const existingIndex = prev.messages.findIndex(
          (m) => m.id === output.id,
        );
        if (existingIndex >= 0) {
          // Update existing - deep merge to preserve nested data like traceData
          const newMessages = [...prev.messages];
          const existingMessage = newMessages[existingIndex];
          newMessages[existingIndex] = {
            ...existingMessage,
            ...output,
            // Deep merge traceData if both exist
            traceData: output.traceData || existingMessage.traceData,
          } as AgentOptimizerMessage;
          console.log(
            "[TraceAgentOptimizer] Updated message ID:",
            output.id,
            "traceData:",
            !!newMessages[existingIndex].traceData,
          );
          return { ...prev, messages: newMessages };
        } else {
          // Add new
          const newMessage = output as AgentOptimizerMessage;
          console.log(
            "[TraceAgentOptimizer] Adding new message ID:",
            output.id,
          );
          return {
            ...prev,
            messages: [...prev.messages, newMessage],
          };
        }
      });
    },
    [],
  );

  const startSession = useCallback(async () => {
    const abortController = startStreaming();

    try {
      const { error } = await runStreaming({
        signal: abortController.signal,
        onAddChunk: addOrUpdateMessage,
      });

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
      stopStreaming();
    }
  }, [runStreaming, toast, stopStreaming, startStreaming, addOrUpdateMessage]);

  const handleStartFresh = useCallback(() => {
    console.log("[TraceAgentOptimizer] Starting fresh - clearing session");
    stopStreaming();

    // First, clear local state immediately
    setChat((prev) => ({ ...prev, value: "", messages: [] }));
    setHasAutoStarted(true); // Prevent auto-start from firing, we'll manually start

    // Remove the query cache completely (not just invalidate) to prevent stale data
    queryClient.removeQueries({
      queryKey: [AGENT_OPTIMIZER_HISTORY_KEY, { traceId }],
    });

    // Then delete the backend session
    deleteMutate(
      { traceId },
      {
        onSuccess: () => {
          console.log(
            "[TraceAgentOptimizer] Session deleted successfully, starting new session...",
          );
          startSession();
        },
      },
    );
  }, [traceId, deleteMutate, stopStreaming, startSession, queryClient]);

  const handleUserResponse = useCallback(
    async (response: UserResponse) => {
      console.log(
        "[TraceAgentOptimizer] handleUserResponse - Type:",
        response.responseType,
        "Data:",
        response.data,
      );
      const abortController = startStreaming();

      try {
        const { error } = await runStreaming({
          response,
          signal: abortController.signal,
          onAddChunk: addOrUpdateMessage,
        });

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
        stopStreaming();
      }
    },
    [runStreaming, toast, stopStreaming, startStreaming, addOrUpdateMessage],
  );

  // Start session on mount if no messages and no history (only on initial load)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    const historyMessages = historyData?.content || [];
    const debugInfo = {
      noMessages,
      isHistoryPending,
      isRunning,
      hasAutoStarted,
      historyMessagesLength: historyMessages.length,
      chatMessagesLength: chat.messages.length,
      willAutoStart:
        noMessages &&
        !isHistoryPending &&
        !isRunning &&
        !hasAutoStarted &&
        historyMessages.length === 0,
    };
    console.log(
      "[TraceAgentOptimizer] Auto-start useEffect triggered:",
      debugInfo,
    );

    if (noMessages && !isHistoryPending && !isRunning && !hasAutoStarted) {
      // Only auto-start if there's no history
      if (historyMessages.length === 0) {
        console.log("[TraceAgentOptimizer] Auto-starting session...");
        startSession();
        setHasAutoStarted(true);
      } else {
        console.log(
          "[TraceAgentOptimizer] History exists, not auto-starting. History length:",
          historyMessages.length,
        );
      }
    }
    // Only run when these specific values change, not when startSession changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    noMessages,
    isHistoryPending,
    isRunning,
    hasAutoStarted,
    historyData?.content,
  ]);

  const renderEmptyState = () => {
    return (
      <div className="flex min-h-full flex-col items-center justify-center gap-3 py-2">
        <div className="comet-title-m text-center text-foreground">
          Agent Optimizer
        </div>
        <div className="comet-body-s mb-8 text-center text-muted-slate">
          Optimize your agent prompts to fix issues and improve performance. The
          optimizer will guide you through the process.
        </div>
      </div>
    );
  };

  return (
    <DetailsActionSectionLayout
      title="Agent Optimizer"
      closeTooltipContent="Close Agent Optimizer"
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
              onClick={() => setShowStartFreshConfirm(true)}
              disabled={noMessages || isRunning}
            >
              <RefreshCw className="mr-2 size-4" />
              Start fresh
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
                {chat.messages.map((m, idx) => (
                  <OptimizerChatMessage
                    key={m.id}
                    message={m}
                    onRespond={handleUserResponse}
                    isLastMessage={idx === chat.messages.length - 1}
                  />
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Chat input area */}
        <div className="flex max-h-[50%] min-h-0 flex-col border-t bg-background">
          <div className="flex-1 overflow-y-auto px-6 py-3">
            <OptimizerChatInput
              chat={chat}
              isRunning={isRunning}
              onUpdateChat={(changes) =>
                setChat((prev) => ({ ...prev, ...changes }))
              }
              onStop={stopStreaming}
            />
          </div>
        </div>
      </div>
      <ConfirmDialog
        open={showStartFreshConfirm}
        setOpen={setShowStartFreshConfirm}
        onConfirm={handleStartFresh}
        title="Start fresh?"
        description="This will clear the current session and start a new optimization process from the beginning. You cannot undo this action."
        confirmText="Start Fresh"
        confirmButtonVariant="default"
      />
    </DetailsActionSectionLayout>
  );
};

export default TraceAgentOptimizer;
