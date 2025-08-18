import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import TraceChatMessages from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIAssistantPanel/TraceChatMessages";
import TraceChatInput from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIAssistantPanel/TraceChatInput";
import { ChatLLMessage, LLM_MESSAGE_ROLE, LLMChatType } from "@/types/llm";
import useAppStore from "@/store/AppStore";
import useTraceAnalyzerHistory from "@/api/ai-assistant/useTraceAnalyzerHistory";
import useTraceAnalyzerRunStreaming from "@/api/ai-assistant/useTraceAnalyzerRunStreaming";
import useTraceAnalyzerDeleteSession from "@/api/ai-assistant/useTraceAnalyzerDeleteSession";
import { Button } from "@/components/ui/button";
import { Trash2 } from "lucide-react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/components/shared/Loader/Loader";
import {
  RunStreamingArgs,
  RunStreamingReturn,
} from "@/api/playground/useCompletionProxyStreaming";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
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
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const [chat, setChat] = useState<LLMChatType>({
    value: "",
    messages: [],
    model: "",
    provider: "",
    configs: {},
  });
  const abortControllerRef = useRef<AbortController>();
  const { data: historyData, isPending: isHistoryPending } =
    useTraceAnalyzerHistory({ traceId });

  useEffect(() => {
    const content = historyData?.content || [];
    const messages: ChatLLMessage[] = content.map((m) => ({
      id: m.id,
      role:
        m.role === "user" ? LLM_MESSAGE_ROLE.user : LLM_MESSAGE_ROLE.assistant,
      content: m.content,
    }));
    setChat((prev) => ({ ...prev, messages }));
  }, [historyData]);

  useEffect(() => {
    const controllerAtMount = abortControllerRef.current;
    return () => {
      controllerAtMount?.abort();
    };
  }, []);

  const noMessages = useMemo(() => !chat.messages.length, [chat.messages]);

  const predefinedPrompts = useMemo(
    () => [
      "Analyze this trace and identify any anomalies.",
      "Explain the cause of slowness in this trace and recommend improvements.",
      "Give me a summary of this trace.",
    ],
    [],
  );

  const runStreaming = useTraceAnalyzerRunStreaming({
    traceId,
    workspaceName,
  });

  const handleCopyToInput = useCallback((content: string) => {
    setChat((prev) => ({ ...prev, value: content }));
  }, []);

  const deleteSessionMutation = useTraceAnalyzerDeleteSession();
  const [showClearConfirm, setShowClearConfirm] = useState(false);

  const handleDeleteSession = useCallback(() => {
    // stop any ongoing stream
    abortControllerRef.current?.abort();
    // Optimistically clear local chat UI
    setChat((prev) => ({ ...prev, value: "", messages: [] }));
    deleteSessionMutation.mutate({ traceId });
  }, [traceId, deleteSessionMutation, setChat]);

  const runStreamingFn = useCallback(
    async ({
      messages,
      signal,
      onAddChunk,
    }: RunStreamingArgs): Promise<RunStreamingReturn> => {
      const { opikError } = await runStreaming({
        model: "",
        messages,
        configs: {},
        signal,
        onAddChunk,
      });

      return {
        result: null,
        startTime: "",
        endTime: "",
        usage: null,
        choices: null,
        providerError: null,
        opikError,
        pythonProxyError: null,
      };
    },
    [runStreaming],
  );

  const handleRunPredefinedPrompt = useCallback(
    async (content: string) => {
      const userMessage = generateDefaultLLMPromptMessage({
        role: LLM_MESSAGE_ROLE.user,
        content,
      });

      const assistantMessage = generateDefaultLLMPromptMessage({
        role: LLM_MESSAGE_ROLE.assistant,
        content: "",
      }) as ChatLLMessage;

      const messages: ChatLLMessage[] = [...chat.messages, userMessage];

      setChat((prev) => ({
        ...prev,
        value: "",
        messages: [...messages, { ...assistantMessage, isLoading: true }],
      }));

      abortControllerRef.current = new AbortController();

      try {
        const run = await runStreamingFn({
          model: chat.model,
          messages,
          configs: chat.configs,
          signal: abortControllerRef.current.signal,
          onAddChunk: (output) => {
            setChat((prev) => ({
              ...prev,
              messages: prev.messages.map((m) =>
                m.id === assistantMessage.id ? { ...m, content: output } : m,
              ),
            }));
          },
        });

        setChat((prev) => ({
          ...prev,
          messages: prev.messages.map((m) =>
            m.id === assistantMessage.id ? { ...m, isLoading: false } : m,
          ),
        }));

        const error =
          run.opikError || run.providerError || run.pythonProxyError;
        if (error) {
          throw new Error(error);
        }
      } catch (error) {
        const typedError = error as Error;
        setChat((prev) => ({
          ...prev,
          messages: prev.messages.map((m) =>
            m.id === assistantMessage.id
              ? { ...m, content: typedError.message }
              : m,
          ),
        }));
      } finally {
        abortControllerRef.current?.abort();
        abortControllerRef.current = undefined;
      }
    },
    [chat.messages, chat.model, chat.configs, runStreamingFn],
  );

  return (
    <DetailsActionSectionLayout
      title="Inspect trace (BETA)"
      closeTooltipContent="Close inspect trace"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      <div className="flex items-center justify-end px-6 pb-2">
        <TooltipWrapper content="Clear conversation">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setShowClearConfirm(true)}
            aria-label="Clear chat session"
            disabled={deleteSessionMutation.isPending}
          >
            <Trash2 className="size-4" />
          </Button>
        </TooltipWrapper>
      </div>
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="relative flex-1 overflow-auto">
          <div className="px-6 py-4 pb-28">
            {isHistoryPending ? (
              <Loader />
            ) : noMessages ? (
              <div className="flex h-full items-center justify-center">
                <div className="max-w-2xl space-y-4 text-left">
                  <div>
                    Ready to dig into your trace? Start with the sample query or
                    enter your own below
                  </div>
                  <div className="grid gap-3">
                    {predefinedPrompts.map((prompt) => (
                      <Button
                        key={prompt}
                        variant="outline"
                        className="h-auto justify-start py-3 text-left"
                        onClick={() => handleRunPredefinedPrompt(prompt)}
                        aria-label={`Use prompt: ${prompt}`}
                      >
                        {prompt}
                      </Button>
                    ))}
                  </div>
                </div>
              </div>
            ) : (
              <TraceChatMessages
                messages={chat.messages}
                onCopyToInput={handleCopyToInput}
              />
            )}
          </div>
          <div className="sticky bottom-0 z-10 border-t px-6 py-3">
            <TraceChatInput
              workspaceName={workspaceName}
              chat={chat}
              abortControllerRef={abortControllerRef}
              withTitle={noMessages}
              runStreamingFn={runStreamingFn}
              onUpdateChat={(changes) =>
                setChat((prev) => ({ ...prev, ...changes }))
              }
              onUpdateMessage={(messageId, changes) =>
                setChat((prev) => ({
                  ...prev,
                  messages: prev.messages.map((m) =>
                    m.id === messageId ? { ...m, ...changes } : m,
                  ),
                }))
              }
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
