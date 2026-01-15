import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { MoreHorizontal, Trash, Loader2 } from "lucide-react";
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
import { TraceAnalyzerLLMMessage, MESSAGE_TYPE } from "@/types/ai-assistant";
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
  const [chat, setChat] = useState<{
    value: string;
    messages: TraceAnalyzerLLMMessage[];
  }>({
    value: "",
    messages: [],
  });
  const [isRunning, setIsRunning] = useState(false);
  const [isStreamingText, setIsStreamingText] = useState(false);
  const [pendingToolCallCount, setPendingToolCallCount] = useState(0);
  const abortControllerRef = useRef<AbortController>();
  const { toast } = useToast();

  // Derived state: thinking when stream is active but nothing visible is happening
  const isThinking =
    isRunning && !isStreamingText && pendingToolCallCount === 0;

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

  const totalContentLength = useMemo(
    () => chat.messages.reduce((acc, msg) => acc + msg.content.length, 0),
    [chat.messages],
  );

  const { scrollContainerRef } = useChatScroll({
    contentLength: totalContentLength,
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
          setChat({ value: "", messages: [] });
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

      setChat((prev) => ({
        ...prev,
        messages: [...prev.messages, userMessage],
      }));

      const abortController = startStreaming();

      // Track current in-progress text message using a wrapper object
      // so TypeScript can track mutations across async boundaries
      type TextMessageState = {
        messageId: string;
        accumulatedContent: string;
        finalized: boolean;
      };
      const textMessageState: { current: TextMessageState | null } = {
        current: null,
      };

      try {
        const { error } = await runStreaming({
          message: content,
          signal: abortController.signal,
          onAddChunk: (output) => {
            const {
              messageType,
              content: chunkContent,
              partial,
              toolCall,
              toolResponse,
            } = output;

            switch (messageType) {
              case MESSAGE_TYPE.response: {
                // Skip if no content (should not happen for response events)
                if (!chunkContent) break;

                // Simple streaming logic:
                // - Partial chunk: append to current message (create if needed or if previous was finalized)
                // - Final (non-partial): replace with complete text and mark finalized

                const isPartial = partial === true;
                const needsNewMessage =
                  !textMessageState.current ||
                  textMessageState.current.finalized;

                if (isPartial) {
                  // Text chunk arrived - we're now streaming text
                  setIsStreamingText(true);

                  // Streaming chunk
                  if (needsNewMessage) {
                    // Start a new message
                    const newMessage = generateDefaultLLMPromptMessage({
                      role: LLM_MESSAGE_ROLE.assistant,
                      content: chunkContent,
                    }) as TraceAnalyzerLLMMessage;
                    newMessage.messageType = messageType;
                    newMessage.isLoading = true;

                    textMessageState.current = {
                      messageId: newMessage.id,
                      accumulatedContent: chunkContent,
                      finalized: false,
                    };

                    setChat((prev) => ({
                      ...prev,
                      messages: [...prev.messages, newMessage],
                    }));
                  } else if (textMessageState.current) {
                    // Append to existing message
                    textMessageState.current.accumulatedContent += chunkContent;

                    updateMessage(textMessageState.current.messageId, {
                      content: textMessageState.current.accumulatedContent,
                      isLoading: true,
                    });
                  }
                } else {
                  // Final message (partial: false) - contains complete text
                  // Text streaming is done for this message
                  setIsStreamingText(false);

                  if (needsNewMessage) {
                    // No streaming happened, create message directly with final content
                    const newMessage = generateDefaultLLMPromptMessage({
                      role: LLM_MESSAGE_ROLE.assistant,
                      content: chunkContent,
                    }) as TraceAnalyzerLLMMessage;
                    newMessage.messageType = messageType;
                    newMessage.isLoading = false;

                    textMessageState.current = {
                      messageId: newMessage.id,
                      accumulatedContent: chunkContent,
                      finalized: true,
                    };

                    setChat((prev) => ({
                      ...prev,
                      messages: [...prev.messages, newMessage],
                    }));
                  } else if (textMessageState.current) {
                    // Finalize existing message with complete text
                    textMessageState.current.accumulatedContent = chunkContent;
                    textMessageState.current.finalized = true;

                    updateMessage(textMessageState.current.messageId, {
                      content: chunkContent,
                      messageType: messageType,
                      isLoading: false,
                    });
                  }
                }
                break;
              }

              case MESSAGE_TYPE.tool_call: {
                // Add tool call to messages as a tool_call message type
                // Group tool calls into a single message or add to existing tool call message
                if (toolCall) {
                  // Track pending tool call
                  setPendingToolCallCount((prev) => prev + 1);

                  setChat((prev) => {
                    const messages = [...prev.messages];
                    const lastMessage = messages[messages.length - 1];

                    // Check if last message is a tool_call message we can add to
                    if (
                      lastMessage &&
                      lastMessage.messageType === MESSAGE_TYPE.tool_call &&
                      lastMessage.toolCalls
                    ) {
                      // Add to existing tool calls message
                      const updatedToolCalls = [
                        ...lastMessage.toolCalls,
                        { ...toolCall, completed: false },
                      ];
                      messages[messages.length - 1] = {
                        ...lastMessage,
                        toolCalls: updatedToolCalls,
                      };
                    } else {
                      // Create new tool calls message
                      const toolCallMessage: TraceAnalyzerLLMMessage = {
                        id: `tool-calls-${Date.now()}`,
                        role: LLM_MESSAGE_ROLE.assistant,
                        content: "",
                        messageType: MESSAGE_TYPE.tool_call,
                        toolCalls: [{ ...toolCall, completed: false }],
                      };
                      messages.push(toolCallMessage);
                    }

                    return {
                      ...prev,
                      messages,
                    };
                  });
                }
                break;
              }

              case MESSAGE_TYPE.tool_complete: {
                // Mark tool call as completed in the tool calls message
                if (toolResponse) {
                  // Decrement pending count
                  setPendingToolCallCount((prev) => Math.max(0, prev - 1));

                  setChat((prev) => {
                    const messages = prev.messages.map((msg) => {
                      if (
                        msg.messageType === MESSAGE_TYPE.tool_call &&
                        msg.toolCalls
                      ) {
                        const updatedToolCalls = msg.toolCalls.map((tc) =>
                          tc.id === toolResponse.id
                            ? { ...tc, completed: true }
                            : tc,
                        );
                        return { ...msg, toolCalls: updatedToolCalls };
                      }
                      return msg;
                    });

                    return {
                      ...prev,
                      messages,
                    };
                  });
                }
                break;
              }
            }
          },
        });

        // Mark current message as complete if still loading
        if (textMessageState.current && !textMessageState.current.finalized) {
          updateMessage(textMessageState.current.messageId, {
            isLoading: false,
          });
        }

        if (error) {
          throw new Error(error);
        }
      } catch (error) {
        const typedError = error as Error;

        // Mark current message as error or create new error message
        if (textMessageState.current) {
          updateMessage(textMessageState.current.messageId, {
            content: typedError.message,
            isLoading: false,
            isError: true,
          });
        } else {
          // Create error message if no response was started
          const errorMessage = generateDefaultLLMPromptMessage({
            role: LLM_MESSAGE_ROLE.assistant,
            content: typedError.message,
          }) as TraceAnalyzerLLMMessage;
          errorMessage.isError = true;

          setChat((prev) => ({
            ...prev,
            messages: [...prev.messages, errorMessage],
          }));
        }

        toast({
          title: "Error",
          variant: "destructive",
          description: typedError.message,
        });
      } finally {
        // Reset streaming states - always runs regardless of success/error
        setIsStreamingText(false);
        setPendingToolCallCount(0);
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
                {isThinking && (
                  <div className="mb-2 flex justify-start">
                    <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-br-xl bg-muted/30 px-4 py-2">
                      <div className="flex items-center gap-2 text-muted-foreground">
                        <Loader2 className="size-3 animate-spin" />
                        <span className="comet-body-xs">Thinking</span>
                      </div>
                    </div>
                  </div>
                )}
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
