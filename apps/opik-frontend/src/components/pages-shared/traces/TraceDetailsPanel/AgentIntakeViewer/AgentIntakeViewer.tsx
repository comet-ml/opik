import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  MoreHorizontal,
  Trash,
  Loader2,
  CheckCircle2,
  Check,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { useToast } from "@/components/ui/use-toast";
import { v4 as uuidv4 } from "uuid";

import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
  DetailsActionSection,
} from "@/components/pages-shared/traces/DetailsActionSection";
import IntakeChatInput from "./IntakeChatInput";
import IntakeChatMessage from "./IntakeChatMessage";
import useIntakeSessionStore from "./IntakeSessionStore";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import {
  useIntakeStart,
  useIntakeRespond,
  useIntakeDelete,
} from "@/api/agent-intake/useIntakeStreaming";
import { useOptimizeStart } from "@/api/agent-intake/useOptimizeStreaming";
import useEndpoints from "@/api/endpoints/useEndpoints";
import OptimizationProgress from "./OptimizationProgress";
import OptimizationChangesPanel from "./OptimizationChangesPanel";
import CommitSuccessPanel from "./CommitSuccessPanel";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { IntakeMessageMetadata, IntakeConfig, INPUT_HINT } from "@/types/agent-intake";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import useAppStore from "@/store/AppStore";

interface AgentIntakeViewerProps {
  traceId: string;
  projectId: string;
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
}

const AgentIntakeViewer: React.FC<AgentIntakeViewerProps> = ({
  traceId,
  projectId,
  activeSection,
  setActiveSection,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    getSession,
    updateSession,
    addMessage,
    updateMessage,
    updateOptimization,
    updateOrAddRun,
    clearSession,
  } = useIntakeSessionStore();
  const session = getSession(traceId);
  const { messages, hasStarted, isReady, config, inputHint, optimization, selectedEndpoint } = session;

  const [inputValue, setInputValue] = useState("");
  const [isRunning, setIsRunning] = useState(false);
  const [behaviorsExpanded, setBehaviorsExpanded] = useState(true);

  const abortControllerRef = useRef<AbortController>();
  const currentMessageRef = useRef<{ id: string; content: string } | null>(
    null,
  );
  const { toast } = useToast();

  const intakeStart = useIntakeStart(traceId);
  const intakeRespond = useIntakeRespond(traceId);
  const intakeDelete = useIntakeDelete(traceId);
  const optimizeStart = useOptimizeStart(traceId);
  const optimizeAbortRef = useRef<AbortController>();

  const { data: endpointsData, isPending: isEndpointsLoading } = useEndpoints({
    projectId,
  });
  const endpoints = endpointsData?.content || [];

  const totalContentLength = useMemo(
    () => messages.reduce((acc, msg) => acc + msg.content.length, 0),
    [messages],
  );

  const { scrollContainerRef } = useChatScroll({
    contentLength: totalContentLength,
    isStreaming: isRunning,
  });

  const [showClearConfirm, setShowClearConfirm] = useState(false);

  const sendMessageInternal = useCallback(
    async (content: string, callbacks: Parameters<typeof intakeRespond>[1]) => {
      abortControllerRef.current = new AbortController();
      try {
        const { error } = await intakeRespond(
          content,
          callbacks,
          abortControllerRef.current.signal,
        );
        if (error) {
          toast({
            title: "Error",
            variant: "destructive",
            description: error,
          });
        }
      } finally {
        setIsRunning(false);
      }
    },
    [intakeRespond, toast],
  );

  const createStreamCallbacks = useCallback(
    () => ({
      onTextDelta: (content: string) => {
        if (!currentMessageRef.current) {
          const id = uuidv4();
          currentMessageRef.current = { id, content: "" };
          addMessage(traceId, {
            id,
            role: LLM_MESSAGE_ROLE.assistant,
            content: "",
            isLoading: true,
          });
        }
        currentMessageRef.current.content += content;
        const messageId = currentMessageRef.current.id;
        const newContent = currentMessageRef.current.content;
        updateMessage(traceId, messageId, { content: newContent });
      },
      onTextDone: () => {
        if (currentMessageRef.current) {
          const messageId = currentMessageRef.current.id;
          updateMessage(traceId, messageId, { isLoading: false });
          currentMessageRef.current = null;
        }
      },
      onInputHint: (hint: INPUT_HINT) => {
        updateSession(traceId, { inputHint: hint });
      },
      onConfigUpdated: (cfg: IntakeConfig | undefined, ready: boolean) => {
        updateSession(traceId, { config: cfg ?? null, isReady: ready });
      },
      onTurnComplete: (ready: boolean) => {
        setIsRunning(false);
        updateSession(traceId, { isReady: ready });
      },
      onComplete: (cfg: IntakeConfig | undefined) => {
        updateSession(traceId, { config: cfg ?? null, isReady: true });
        setIsRunning(false);
      },
    }),
    [traceId, addMessage, updateMessage, updateSession],
  );

  const startIntake = useCallback(async () => {
    setIsRunning(true);
    updateSession(traceId, { hasStarted: true });
    abortControllerRef.current = new AbortController();

    const { error } = await intakeStart(
      {
        trace_info: {
          has_endpoints: endpoints.length > 0,
        },
      },
      createStreamCallbacks(),
      abortControllerRef.current.signal,
    );

    if (error) {
      toast({
        title: "Error",
        variant: "destructive",
        description: error,
      });
      setIsRunning(false);
    }
  }, [intakeStart, createStreamCallbacks, toast, endpoints, traceId, updateSession]);

  const sendMessage = useCallback(
    async (
      content: string,
      options?: { displayContent?: string; metadata?: IntakeMessageMetadata },
    ) => {
      addMessage(traceId, {
        id: uuidv4(),
        role: LLM_MESSAGE_ROLE.user,
        content: options?.displayContent || content,
        metadata: options?.metadata,
      });
      setInputValue("");
      updateSession(traceId, { inputHint: INPUT_HINT.none });
      setIsRunning(true);

      await sendMessageInternal(content, createStreamCallbacks());
    },
    [sendMessageInternal, createStreamCallbacks, traceId, addMessage, updateSession],
  );

  const handleEndpointSelect = useCallback(
    (endpointId: string) => {
      const endpoint = endpoints.find((e) => e.id === endpointId);
      const displayName = endpoint?.name || endpointId;
      if (endpoint) {
        updateSession(traceId, {
          selectedEndpoint: {
            id: endpoint.id,
            name: endpoint.name,
            url: endpoint.url,
            secret: endpoint.secret,
          },
        });
      }
      sendMessage(endpointId, {
        metadata: { type: "endpoint_selection", endpointName: displayName },
      });
    },
    [endpoints, sendMessage, traceId, updateSession],
  );

  const handleNoEndpointsChoice = useCallback(
    (choice: "run_myself" | "setup_endpoint") => {
      sendMessage(choice, {
        metadata: { type: "no_endpoints_choice", choice },
      });
    },
    [sendMessage],
  );

  const handleClearSession = useCallback(async () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    if (optimizeAbortRef.current) {
      optimizeAbortRef.current.abort();
    }
    await intakeDelete();
    clearSession(traceId);
    setInputValue("");
    setIsRunning(false);
    setBehaviorsExpanded(true);
    optimizationStartedRef.current = false;
  }, [intakeDelete, traceId, clearSession]);

  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, [traceId]);

  useEffect(() => {
    if (activeSection !== DetailsActionSection.AIAssistants) {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      if (optimizeAbortRef.current) {
        optimizeAbortRef.current.abort();
      }
    }
  }, [activeSection]);

  // Start optimization when intake is ready
  const optimizationStartedRef = useRef(false);

  useEffect(() => {
    if (isReady && config && !optimization.isOptimizing && !optimization.isComplete && !optimizationStartedRef.current) {
      optimizationStartedRef.current = true;

      const startOptimization = async () => {
        optimizeAbortRef.current = new AbortController();
        updateOptimization(traceId, { isOptimizing: true });

        const { error } = await optimizeStart(
          {
            expected_behaviors: config.expected_behaviors,
            endpoint: selectedEndpoint
              ? {
                  name: selectedEndpoint.name,
                  url: selectedEndpoint.url,
                  secret: selectedEndpoint.secret,
                }
              : undefined,
          },
          {
            onOptimizationStarted: (expectedBehaviors) => {
              const initialAssertions = expectedBehaviors.map((name) => ({
                name,
              }));
              updateOrAddRun(traceId, {
                label: "Original",
                iteration: 0,
                status: "running",
                assertions: initialAssertions,
                trace_id: traceId,
              });
            },
            onRunStatus: (label, iteration, status, runTraceId) => {
              updateOrAddRun(traceId, {
                label,
                iteration,
                status,
                assertions: [],
                trace_id: runTraceId,
              });
            },
            onRunResult: (label, iteration, allPassed, assertions, runTraceId) => {
              updateOrAddRun(traceId, {
                label,
                iteration,
                status: "completed",
                all_passed: allPassed,
                assertions,
                trace_id: runTraceId,
              });
            },
            onRegressionResult: (iteration, regressionResult) => {
              updateOrAddRun(traceId, {
                label: iteration === 0 ? "Original" : `Iteration ${iteration}`,
                iteration,
                status: "completed",
                assertions: [],
                regression: regressionResult,
              });
            },
            onOptimizationComplete: (result) => {
              updateOptimization(traceId, {
                isOptimizing: false,
                isComplete: true,
                success: result.success,
                changes: result.changes,
                optimizationId: result.optimizationId,
                promptChanges: result.promptChanges,
                scalarChanges: result.scalarChanges,
                experimentTraces: result.experimentTraces,
                finalAssertionResults: result.finalAssertionResults,
              });
            },
          },
          optimizeAbortRef.current.signal,
        );

        if (error) {
          toast({
            title: "Optimization Error",
            variant: "destructive",
            description: error,
          });
          updateOptimization(traceId, { isOptimizing: false });
        }
      };

      startOptimization();
    }
  }, [
    isReady,
    config,
    selectedEndpoint,
    optimization.isOptimizing,
    optimization.isComplete,
    traceId,
    optimizeStart,
    updateOptimization,
    updateOrAddRun,
    toast,
  ]);

  const renderEmptyState = () => (
    <div className="flex min-h-full flex-col items-center justify-center gap-3 py-2">
      <div className="comet-title-m text-center text-foreground">
        ✨ Optimize Your Agent
      </div>
      <div className="comet-body-s mb-8 text-center text-muted-slate">
        Let's gather some information about how you want to optimize this trace.
        We'll ask a few questions to set up your optimization configuration.
      </div>
      <Button variant="default" onClick={startIntake}>
        Start Optimization Setup
      </Button>
    </div>
  );

  const expectedBehaviors = config?.expected_behaviors || [];
  const hasExpectedBehaviors = expectedBehaviors.length > 0;

  const renderExpectedBehaviors = () => {
    if (!hasExpectedBehaviors) return null;

    return (
      <div className="mb-4 rounded-lg border border-primary/20 bg-primary/5">
        <button
          type="button"
          className="flex w-full items-center gap-2 p-3"
          onClick={() => setBehaviorsExpanded(!behaviorsExpanded)}
        >
          {behaviorsExpanded ? (
            <ChevronDown className="size-4 text-primary" />
          ) : (
            <ChevronRight className="size-4 text-primary" />
          )}
          <div className="flex flex-1 items-center gap-2">
            <span className="comet-body-s-accented text-foreground">
              Expected Behaviors
            </span>
            <span className="comet-body-xs rounded-full bg-primary/20 px-2 py-0.5 text-primary">
              {expectedBehaviors.length}
            </span>
          </div>
          {isReady && (
            <CheckCircle2 className="size-4 text-green-600" />
          )}
        </button>
        {behaviorsExpanded && (
          <div className="border-t border-primary/10 px-3 pb-3">
            <ul className="mt-2 space-y-1.5">
              {expectedBehaviors.map((behavior, index) => (
                <li
                  key={index}
                  className="flex items-start gap-2 text-sm text-foreground"
                >
                  <Check className="mt-0.5 size-3.5 shrink-0 text-green-600" />
                  <span>{behavior}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    );
  };

  return (
    <DetailsActionSectionLayout
      title="Agent Optimization"
      closeTooltipContent="Close optimization panel"
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
              disabled={messages.length === 0}
            >
              <Trash className="mr-2 size-4" />
              Clear conversation
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      }
    >
      <div className="flex h-full flex-col overflow-hidden py-4">
        <div className="flex flex-1 flex-col overflow-hidden border-t">
          <div className="flex-1 overflow-y-auto px-6" ref={scrollContainerRef}>
            {!hasStarted ? (
              renderEmptyState()
            ) : (
              <div className="flex w-full flex-col gap-2 py-4">
                {renderExpectedBehaviors()}
                {messages.map((m) => (
                  <IntakeChatMessage key={m.id} message={m} />
                ))}
                {isRunning && !currentMessageRef.current && (
                  <div className="mb-2 flex justify-start">
                    <div className="relative min-w-[20%] max-w-[90%] rounded-t-xl rounded-br-xl bg-muted/30 px-4 py-2">
                      <div className="flex items-center gap-2 text-muted-foreground">
                        <Loader2 className="size-3 animate-spin" />
                        <span className="comet-body-xs">Thinking</span>
                      </div>
                    </div>
                  </div>
                )}
                {(optimization.isOptimizing || optimization.isComplete) && (
                  <>
                    <OptimizationProgress
                      runs={optimization.runs}
                      isOptimizing={optimization.isOptimizing}
                      isComplete={optimization.isComplete && !optimization.promptChanges?.length && !optimization.scalarChanges?.length}
                      success={optimization.success}
                      cancelled={optimization.cancelled}
                      changes={optimization.changes}
                      finalAssertionResults={optimization.finalAssertionResults}
                      workspaceName={workspaceName}
                      projectId={projectId}
                      onCancel={
                        optimization.isOptimizing
                          ? () => {
                              if (optimizeAbortRef.current) {
                                optimizeAbortRef.current.abort();
                              }
                              updateOptimization(traceId, {
                                isOptimizing: false,
                                isComplete: true,
                                success: false,
                                cancelled: true,
                              });
                            }
                          : undefined
                      }
                    />
                    {optimization.isComplete &&
                      optimization.success &&
                      ((optimization.promptChanges && optimization.promptChanges.length > 0) ||
                       (optimization.scalarChanges && optimization.scalarChanges.length > 0)) &&
                      !optimization.commitResult && (
                        <OptimizationChangesPanel
                          optimizationId={optimization.optimizationId || ""}
                          promptChanges={optimization.promptChanges || []}
                          scalarChanges={optimization.scalarChanges}
                          onCommitComplete={(result) => {
                            updateOptimization(traceId, { commitResult: result });
                          }}
                          onDiscard={() => {
                            updateOptimization(traceId, {
                              promptChanges: [],
                              scalarChanges: [],
                            });
                          }}
                        />
                      )}
                    {optimization.commitResult && (
                      <CommitSuccessPanel
                        result={optimization.commitResult}
                      />
                    )}
                  </>
                )}
                {inputHint === INPUT_HINT.endpoint_selector && !isRunning && (
                  <div className="mb-2 flex justify-start">
                    <div className="min-w-48 max-w-72">
                      {isEndpointsLoading ? (
                        <div className="flex items-center gap-2 text-muted-slate">
                          <Loader2 className="size-4 animate-spin" />
                          <span className="comet-body-s">
                            Loading endpoints...
                          </span>
                        </div>
                      ) : endpoints.length === 0 ? (
                        <div className="text-muted-slate comet-body-s">
                          No endpoints configured for this project.
                        </div>
                      ) : (
                        <Select
                          onValueChange={handleEndpointSelect}
                          disabled={isRunning}
                        >
                          <SelectTrigger className="w-full">
                            <SelectValue placeholder="Select an endpoint..." />
                          </SelectTrigger>
                          <SelectContent>
                            {endpoints.map((endpoint) => (
                              <SelectItem key={endpoint.id} value={endpoint.id}>
                                {endpoint.name}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      )}
                    </div>
                  </div>
                )}
                {inputHint === INPUT_HINT.yes_no && !isRunning && (
                  <div className="mb-2 flex justify-start gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => sendMessage("yes")}
                    >
                      Yes
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => sendMessage("no")}
                    >
                      No
                    </Button>
                  </div>
                )}
                {inputHint === INPUT_HINT.no_endpoints_configured && !isRunning && (
                  <div className="mb-2 flex justify-start gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleNoEndpointsChoice("run_myself")}
                    >
                      Run it myself
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleNoEndpointsChoice("setup_endpoint")}
                    >
                      Setup an endpoint
                    </Button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>

        {hasStarted && !isReady && (
          <div className="flex max-h-[50%] min-h-0 flex-col border-t bg-background">
            <div className="flex-1 overflow-y-auto px-6 py-3">
              <IntakeChatInput
                value={inputValue}
                isRunning={isRunning}
                inputHint={inputHint}
                onValueChange={setInputValue}
                onSend={sendMessage}
              />
            </div>
          </div>
        )}
      </div>
      <ConfirmDialog
        open={showClearConfirm}
        setOpen={setShowClearConfirm}
        onConfirm={handleClearSession}
        title="Clear conversation?"
        description="This will remove the current optimization session. You cannot undo this action."
        confirmText="Clear"
        confirmButtonVariant="destructive"
      />
    </DetailsActionSectionLayout>
  );
};

export default AgentIntakeViewer;
