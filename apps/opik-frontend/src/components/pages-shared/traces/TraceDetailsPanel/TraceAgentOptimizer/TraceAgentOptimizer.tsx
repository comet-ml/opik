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
import useAgentOptimizerHistory from "@/api/agent-optimizer/useAgentOptimizerHistory";
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
  const abortControllerRef = useRef<AbortController>();
  const { toast } = useToast();
  const scrollContainerRef = useRef<HTMLDivElement>(null);

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

  // Auto-scroll
  useEffect(() => {
    if (scrollContainerRef.current && isRunning) {
      scrollContainerRef.current.scrollTop = scrollContainerRef.current.scrollHeight;
    }
  }, [chat.messages, isRunning]);

  const noMessages = useMemo(() => !chat.messages.length, [chat.messages]);

  const runStreaming = useAgentOptimizerRunStreaming({ traceId });

  const { mutate: deleteMutate } = useAgentOptimizerDeleteSession();
  const [showClearConfirm, setShowClearConfirm] = useState(false);

  const updateMessage = useCallback(
    (messageId: string, updates: Partial<AgentOptimizerMessage>) => {
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

  const startSession = useCallback(async () => {
    const abortController = startStreaming();

    try {
      const { error } = await runStreaming({
        signal: abortController.signal,
        onAddChunk: (output) => {
          if (output.id) {
            // Check if message exists
            setChat((prev) => {
              const existingIndex = prev.messages.findIndex(
                (m) => m.id === output.id,
              );
              if (existingIndex >= 0) {
                // Update existing
                const newMessages = [...prev.messages];
                newMessages[existingIndex] = {
                  ...newMessages[existingIndex],
                  ...output,
                } as AgentOptimizerMessage;
                return { ...prev, messages: newMessages };
              } else {
                // Add new
                return {
                  ...prev,
                  messages: [...prev.messages, output as AgentOptimizerMessage],
                };
              }
            });
          }
        },
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
  }, [runStreaming, toast, stopStreaming, startStreaming]);

  const handleUserResponse = useCallback(
    async (response: UserResponse) => {
      const abortController = startStreaming();

      try {
        const { error } = await runStreaming({
          response,
          signal: abortController.signal,
          onAddChunk: (output) => {
            if (output.id) {
              setChat((prev) => {
                const existingIndex = prev.messages.findIndex(
                  (m) => m.id === output.id,
                );
                if (existingIndex >= 0) {
                  const newMessages = [...prev.messages];
                  newMessages[existingIndex] = {
                    ...newMessages[existingIndex],
                    ...output,
                  } as AgentOptimizerMessage;
                  return { ...prev, messages: newMessages };
                } else {
                  return {
                    ...prev,
                    messages: [...prev.messages, output as AgentOptimizerMessage],
                  };
                }
              });
            }
          },
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
    [runStreaming, toast, stopStreaming, startStreaming],
  );

  // Start session on mount if no messages
  useEffect(() => {
    if (noMessages && !isHistoryPending && !isRunning) {
      startSession();
    }
  }, [noMessages, isHistoryPending, isRunning, startSession]);

  const renderEmptyState = () => {
    return (
      <div className="flex min-h-full flex-col items-center justify-center gap-3 py-2">
        <div className="comet-title-m text-center text-foreground">
          Agent Optimizer
        </div>
        <div className="comet-body-s mb-8 text-center text-muted-slate">
          Optimize your agent prompts to fix issues and improve performance.
          The optimizer will guide you through the process.
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
                  <OptimizerChatMessage
                    key={m.id}
                    message={m}
                    onRespond={handleUserResponse}
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
        open={showClearConfirm}
        setOpen={setShowClearConfirm}
        onConfirm={handleDeleteSession}
        title="Clear conversation?"
        description="This will remove the current optimizer session for this trace. You cannot undo this action."
        confirmText="Clear"
        confirmButtonVariant="destructive"
      />
    </DetailsActionSectionLayout>
  );
};

export default TraceAgentOptimizer;
