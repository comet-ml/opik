import React, { useState, useMemo, useCallback, useEffect } from "react";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import useAppStore from "@/store/AppStore";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import ContextSelectionSection from "./ContextSelectionSection";
import CustomViewChatPanel from "./CustomViewChatPanel";
import CustomViewDataPanel from "./CustomViewDataPanel";
import useConversationalAI from "@/api/custom-view/useConversationalAI";
import useSchemaGenerationAI from "@/api/custom-view/useSchemaGenerationAI";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import { ChatDisplayMessage } from "@/types/structured-completion";
import {
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { STRUCTURED_OUTPUT_SUPPORTED_MODELS } from "@/constants/llm";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  ProposalState,
  SCHEMA_PROPOSAL_TOOL,
  ProposeSchemaToolArguments,
} from "@/types/schema-proposal";
import { customViewStorage } from "@/lib/customViewStorage";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";
import SaveViewButton from "./SaveViewButton";
import { Save, X, CheckCircle2 } from "lucide-react";
import { CustomViewProvider, useCustomViewContext } from "./CustomViewContext";

// System prompt for conversational AI
const CHAT_SYSTEM_PROMPT = `You are an AI assistant that helps users understand and visualize LLM data (traces and threads).

Your role is to:
1. Answer questions about the data structure and content
2. Help users understand what fields are available
3. Suggest what might be interesting to visualize
4. When the user wants to create or update a view, use the propose_schema_generation tool

The data structure is provided in the context. Be conversational and helpful.`;

const CustomViewDemoPageContent: React.FC = () => {
  const projectId = useProjectIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Get state from context
  const {
    contextType,
    setContextType,
    selectedTraceId,
    selectedThreadId,
    setSelectedTraceId,
    setSelectedThreadId,
    contextData,
    isContextLoading,
    isContextError,
    model,
    provider,
    setModel,
    setProvider,
    viewSchema,
    viewSource,
    setViewSchema,
    setViewSource,
  } = useCustomViewContext();

  // Chat input state
  const [inputValue, setInputValue] = useState("");

  // View saving state
  const [isSaving, setIsSaving] = useState(false);

  // Proposal state machine
  const [proposalState, setProposalState] = useState<ProposalState>({
    status: "idle",
  });

  // Toast for notifications
  const { toast } = useToast();

  // Model configuration state
  const [configs, setConfigs] = useState<Partial<LLMPromptConfigsType>>(() =>
    getDefaultConfigByProvider(
      (provider as COMPOSED_PROVIDER_TYPE) || "",
      (model as PROVIDER_MODEL_TYPE) || "",
    ),
  );

  // Update configs when model or provider changes
  useEffect(() => {
    if (provider && model) {
      setConfigs(
        getDefaultConfigByProvider(
          provider as COMPOSED_PROVIDER_TYPE,
          model as PROVIDER_MODEL_TYPE,
        ),
      );
    }
  }, [provider, model]);

  // Initialize conversational AI hook (Chat AI with tools)
  const conversationalAI = useConversationalAI({
    workspaceName,
  });

  // Initialize schema generation AI hook
  const schemaGenerationAI = useSchemaGenerationAI({
    workspaceName,
  });

  // Transform conversational AI messages to display messages
  const displayMessages = useMemo<ChatDisplayMessage[]>(() => {
    return conversationalAI.messages.map((msg, index) => ({
      id: `msg-${index}`,
      role: msg.role as "user" | "assistant",
      content: msg.content,
    }));
  }, [conversationalAI.messages]);

  // Add loading message if currently loading
  const displayMessagesWithLoading = useMemo<ChatDisplayMessage[]>(() => {
    if (conversationalAI.isLoading && displayMessages.length > 0) {
      // Check if last message is from user (waiting for response)
      const lastMessage = displayMessages[displayMessages.length - 1];
      if (lastMessage.role === "user") {
        return [
          ...displayMessages,
          {
            id: "loading",
            role: "assistant" as const,
            content: "",
            isLoading: true,
          },
        ];
      }
    }
    return displayMessages;
  }, [displayMessages, conversationalAI.isLoading]);

  // Calculate total content length for auto-scroll
  const totalContentLength = useMemo(
    () =>
      displayMessagesWithLoading.reduce(
        (acc, msg) => acc + msg.content.length,
        0,
      ),
    [displayMessagesWithLoading],
  );

  // Setup chat scroll behavior
  const { scrollContainerRef } = useChatScroll({
    contentLength: totalContentLength,
    isStreaming: conversationalAI.isLoading,
  });

  // Handle config change
  const handleConfigChange = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      setConfigs(newConfigs);
    },
    [],
  );

  // Handle sending a message
  const handleSend = useCallback(async () => {
    if (!inputValue.trim() || !model || !contextData) return;

    const userMessage = inputValue;
    setInputValue("");

    const response = await conversationalAI.generate({
      model: model || "",
      userMessage,
      systemPrompt: CHAT_SYSTEM_PROMPT,
      configs,
      tools: [SCHEMA_PROPOSAL_TOOL],
    });

    // Check if AI returned a tool call (schema proposal)
    if (response?.toolCalls && response.toolCalls.length > 0) {
      const toolCall = response.toolCalls[0];
      if (toolCall.function.name === "propose_schema_generation") {
        try {
          const args = JSON.parse(
            toolCall.function.arguments,
          ) as ProposeSchemaToolArguments;
          setProposalState({
            status: "pending",
            proposal: {
              id: toolCall.id,
              intentSummary: args.intent_summary,
              action: args.action,
            },
          });
        } catch (error) {
          console.error("Failed to parse tool call arguments:", error);
        }
      }
    }
  }, [inputValue, model, contextData, configs, conversationalAI]);

  // Handle retry - resend the last failed request
  const handleRetry = useCallback(async () => {
    // Retry not implemented for conversational AI yet
    conversationalAI.reset();
  }, [conversationalAI]);

  // Handle input change
  const handleInputChange = useCallback((value: string) => {
    setInputValue(value);
  }, []);

  // Handle accepting a proposal
  const handleAcceptProposal = useCallback(async () => {
    if (proposalState.status !== "pending") return;
    if (!model || !contextData) return;

    // Set to generating state
    setProposalState({
      status: "generating",
      proposal: proposalState.proposal,
    });

    // Generate schema using Schema Generation AI
    const schema = await schemaGenerationAI.generateSchema({
      model: model || "",
      context: {
        intentSummary: proposalState.proposal.intentSummary,
        action: proposalState.proposal.action,
        data: contextData,
        dataType: contextType,
        model: model || "",
        currentSchema: viewSchema,
      },
    });

    if (schema) {
      setViewSchema(schema);
      setViewSource("ai");
      toast({
        title: "Schema generated",
        description: "Custom view has been created successfully",
      });
    } else {
      toast({
        variant: "destructive",
        title: "Failed to generate schema",
        description: schemaGenerationAI.error || "Unknown error",
      });
    }

    // Reset to idle
    setProposalState({ status: "idle" });
  }, [
    proposalState,
    model,
    contextData,
    contextType,
    viewSchema,
    schemaGenerationAI,
    toast,
    setViewSchema,
    setViewSource,
  ]);

  // Handle rejecting a proposal
  const handleRejectProposal = useCallback(() => {
    if (proposalState.status !== "pending") return;

    setProposalState({ status: "idle" });
    toast({
      title: "Proposal rejected",
      description: "You can continue chatting",
    });
  }, [proposalState, toast]);

  // Handle saving view schema to localStorage
  const handleSaveView = useCallback(() => {
    if (!viewSchema || !projectId) return;

    setIsSaving(true);
    try {
      customViewStorage.save(projectId, viewSchema);
      setViewSource("saved");
      toast({
        title: "View saved",
        description: "Custom view saved for this project",
      });
    } catch (error) {
      console.error("Failed to save view:", error);
      toast({
        variant: "destructive",
        title: "Failed to save view",
        description: "Could not save view. Please try again.",
      });
    } finally {
      setIsSaving(false);
    }
  }, [viewSchema, projectId, toast, setViewSource]);

  // Handle clearing saved view
  const handleClearSaved = useCallback(() => {
    if (!projectId) return;

    customViewStorage.delete(projectId);
    setViewSchema(null);
    setViewSource("empty");
    toast({
      title: "Saved view cleared",
      description: "Saved view removed. Generate a new one or switch context.",
    });
  }, [projectId, toast, setViewSchema, setViewSource]);

  return (
    <div className="flex h-[calc(100vh-var(--header-height)-var(--banner-height))] flex-col">
      <PageBodyStickyContainer className="mb-4 mt-6 px-0">
        <div className="flex items-end justify-between gap-4">
          {/* Left section: Model, Trace, Config */}
          <div className="flex items-end gap-2">
            <div className="h-full w-80">
              <label className="comet-body-s-accented mb-2 block">
                Model (Structured Output)
              </label>
              <div className="h-8">
                <PromptModelSelect
                  value={(model as PROVIDER_MODEL_TYPE) || ""}
                  provider={provider || ""}
                  workspaceName={workspaceName}
                  onChange={(newModel, newProvider) => {
                    setModel(newModel);
                    setProvider(newProvider);
                  }}
                  modelFilter={(model, provider) => {
                    const supportedModels =
                      STRUCTURED_OUTPUT_SUPPORTED_MODELS[provider] || [];
                    return supportedModels.includes(model);
                  }}
                />
              </div>
            </div>

            <ContextSelectionSection
              projectId={projectId}
              contextType={contextType}
              selectedTraceId={selectedTraceId || null}
              selectedThreadId={selectedThreadId || null}
              onContextTypeChange={setContextType}
              onSelectTrace={setSelectedTraceId}
              onSelectThread={setSelectedThreadId}
            />

            <PromptModelConfigs
              provider={(provider as COMPOSED_PROVIDER_TYPE) || ""}
              model={(model as PROVIDER_MODEL_TYPE) || ""}
              configs={configs}
              onChange={handleConfigChange}
            />
          </div>

          {/* Right section: Status + Save controls */}
          <div className="flex items-center gap-2">
            {/* Saved view indicator badge */}
            {viewSource === "saved" && (
              <div className="flex items-center gap-1.5 rounded-md border border-green-200 bg-green-50 px-2.5 py-1 text-sm text-green-700 dark:border-green-800 dark:bg-green-900/20 dark:text-green-400">
                <CheckCircle2 className="size-3.5" />
                <span className="font-medium">Saved View</span>
              </div>
            )}

            {/* AI generated indicator badge */}
            {viewSource === "ai" && (
              <div className="flex items-center gap-1.5 rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 text-sm text-blue-700 dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-400">
                <Save className="size-3.5" />
                <span className="font-medium">Unsaved</span>
              </div>
            )}

            {/* Save button */}
            <SaveViewButton
              schema={viewSchema}
              projectId={projectId}
              viewSource={viewSource}
              onSave={handleSaveView}
              isSaving={isSaving}
            />

            {/* Clear saved view button */}
            {viewSource === "saved" && (
              <Button variant="ghost" size="sm" onClick={handleClearSaved}>
                <X className="mr-1 size-4" />
                Clear
              </Button>
            )}
          </div>
        </div>
      </PageBodyStickyContainer>

      {/* Info banner when using saved view */}
      {viewSource === "saved" && (
        <div className="mb-4 rounded-lg border border-green-200 bg-green-50 p-3 dark:border-green-800 dark:bg-green-900/20">
          <div className="flex items-center gap-2 text-sm text-green-800 dark:text-green-400">
            <CheckCircle2 className="size-4 shrink-0" />
            <span>
              Using saved view for this project. Send a new prompt to generate a
              different view.
            </span>
          </div>
        </div>
      )}

      <div className="flex-1 overflow-hidden">
        <ResizablePanelGroup
          direction="horizontal"
          autoSaveId="custom-view-demo-layout"
        >
          <ResizablePanel id="chat" defaultSize={40} minSize={30}>
            <CustomViewChatPanel
              messages={displayMessagesWithLoading}
              inputValue={inputValue}
              isLoading={conversationalAI.isLoading}
              error={conversationalAI.error}
              onInputChange={handleInputChange}
              onSend={handleSend}
              onRetry={handleRetry}
              scrollContainerRef={scrollContainerRef}
              model={model}
              traceId={selectedTraceId || selectedThreadId}
              proposalState={proposalState}
              onAcceptProposal={handleAcceptProposal}
              onRejectProposal={handleRejectProposal}
            />
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="data" defaultSize={60} minSize={30}>
            <CustomViewDataPanel
              data={contextData}
              viewSchema={viewSchema}
              isDataLoading={isContextLoading}
              isDataError={isContextError}
              isAIGenerating={proposalState.status === "generating"}
              isAPIError={Boolean(conversationalAI.error)}
              apiError={conversationalAI.error}
            />
          </ResizablePanel>
        </ResizablePanelGroup>
      </div>
    </div>
  );
};

// Wrapper component that provides context
const CustomViewDemoPage: React.FC = () => {
  const projectId = useProjectIdFromURL();

  return (
    <CustomViewProvider projectId={projectId}>
      <CustomViewDemoPageContent />
    </CustomViewProvider>
  );
};

export default CustomViewDemoPage;
