import React, { useState, useMemo, useCallback, useEffect } from "react";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { useCustomViewParams } from "@/hooks/useCustomViewParams";
import { useCustomViewData } from "@/hooks/useCustomViewData";
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
import useViewTreeGenerationAI from "@/api/custom-view/useViewTreeGenerationAI";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import { ChatDisplayMessage } from "@/types/structured-completion";
import {
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  ProposalState,
  SCHEMA_PROPOSAL_TOOL,
  ProposeSchemaToolArguments,
} from "@/types/schema-proposal";
import {
  DataViewProvider,
  useDataView,
  useViewStorage,
  loadView,
  deleteView,
  createEmptyTree,
} from "@/lib/data-view";
import { contextDataToSourceData } from "./data-view-widgets/types";
import { ContextData } from "@/types/custom-view";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";
import SaveViewButton from "./SaveViewButton";
import { X, CheckCircle2, MessageSquare, Settings } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import { ViewTree, ViewNode } from "@/lib/data-view";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  useCustomPrompts,
  ConversationalPromptDialog,
  SchemaGenerationPromptDialog,
} from "./prompts";

// Storage key prefix for custom views
const STORAGE_KEY_PREFIX = "custom-view:";

// Base system prompt for conversational AI
const BASE_CHAT_SYSTEM_PROMPT = `You are an AI assistant that helps users understand and visualize LLM data (traces and threads).

Your role is to:
1. Answer questions about the data structure and content
2. Help users understand what fields are available
3. Suggest what might be interesting to visualize
4. When the user wants to create or update a view, you MUST call the propose_schema_generation function

CRITICAL FUNCTION CALLING REQUIREMENTS:
- When the user asks to "build", "create", "generate", "show me", or "make" a view, you MUST invoke the propose_schema_generation function
- You MUST actually make the function call - do not just say "Let me generate that" or "I'll create that for you" without calling the function
- The function call is a separate API mechanism - your text response should acknowledge what you're doing, but the actual function call must also be made
- NEVER respond with only text when a view generation is requested - always include the function call
- If you're uncertain whether to generate a view, ask the user for clarification first

INCORRECT behavior (do not do this):
- Saying "Let me generate that view for you now..." without making a function call
- Outputting XML tags or JSON that looks like a function call
- Only providing text explanation without the actual function invocation

CORRECT behavior:
- When user requests a view: Make the propose_schema_generation function call AND include a brief text acknowledgment
- When user asks questions: Respond with text only (no function call needed)

The data structure is provided in the context. Be conversational and helpful.`;

/**
 * Summarize a ViewTree structure for the conversational AI context.
 * Produces a human-readable list of sections and their children.
 */
const summarizeViewTree = (tree: ViewTree): string => {
  const lines: string[] = [];

  const summarizeNode = (nodeId: string, indent: number = 0): void => {
    const node = tree.nodes[nodeId];
    if (!node) return;

    const prefix = "  ".repeat(indent);
    const label = getNodeLabel(node);
    lines.push(`${prefix}- ${node.type}: ${label} (id: ${nodeId})`);

    if (node.children && node.children.length > 0) {
      node.children.forEach((childId) => summarizeNode(childId, indent + 1));
    }
  };

  if (tree.root && tree.nodes[tree.root]) {
    summarizeNode(tree.root);
  }

  return lines.join("\n");
};

/**
 * Get a human-readable label for a node based on its type and props.
 */
const getNodeLabel = (node: ViewNode): string => {
  const props = node.props as Record<string, unknown>;

  // Try common label props
  if (typeof props.title === "string") return props.title;
  if (typeof props.label === "string") return props.label;
  if (typeof props.name === "string") return props.name;

  // For path refs, show the path
  if (
    props.title &&
    typeof props.title === "object" &&
    "path" in (props.title as object)
  ) {
    return `bound to ${(props.title as { path: string }).path}`;
  }
  if (
    props.content &&
    typeof props.content === "object" &&
    "path" in (props.content as object)
  ) {
    return `bound to ${(props.content as { path: string }).path}`;
  }

  return "(no label)";
};

/**
 * Build a compact data summary for the conversational AI.
 * Shows the actual data structure so the AI can answer questions about the data.
 */
const buildDataSummary = (
  contextData: ContextData | null | undefined,
  contextType: "trace" | "thread",
): string => {
  if (!contextData) return "";

  // For threads with traces, show a helpful summary
  if (contextType === "thread" && "traces" in contextData) {
    const thread = contextData;
    const traceCount = thread.traces?.length ?? 0;

    let summary = `## Thread Data Available:
- Thread ID: ${thread.id}
- Number of messages: ${thread.number_of_messages}
- Duration: ${thread.duration}ms
- Status: ${thread.status}
- Traces array: ${traceCount} traces loaded`;

    // Show structure of first trace if available
    if (thread.traces && thread.traces.length > 0) {
      summary += `\n\n### Trace Structure (each message):`;
      summary += `\n- /traces/N/input - User input for message N`;
      summary += `\n- /traces/N/output - Assistant output for message N`;
      summary += `\n- /traces/N/id, /traces/N/name, /traces/N/duration, etc.`;

      // Show actual first and last message content (truncated)
      const firstTrace = thread.traces[0];
      const lastTrace = thread.traces[thread.traces.length - 1];

      const truncate = (obj: unknown, maxLen: number = 200): string => {
        const str = JSON.stringify(obj);
        return str.length > maxLen ? str.slice(0, maxLen) + "..." : str;
      };

      summary += `\n\n### First message (trace 0):`;
      summary += `\n- Input: ${truncate(firstTrace.input)}`;
      summary += `\n- Output: ${truncate(firstTrace.output)}`;

      if (traceCount > 1) {
        summary += `\n\n### Last message (trace ${traceCount - 1}):`;
        summary += `\n- Input: ${truncate(lastTrace.input)}`;
        summary += `\n- Output: ${truncate(lastTrace.output)}`;
      }
    }

    return summary;
  }

  // For single trace, show a summary of available fields
  if (contextType === "trace" && "input" in contextData) {
    const trace = contextData;
    const keys = Object.keys(trace);
    return `## Trace Data Available:
- Trace ID: ${trace.id}
- Name: ${trace.name}
- Duration: ${trace.duration}ms
- Available fields: ${keys.join(", ")}
- Input: ${JSON.stringify(trace.input).slice(0, 200)}...
- Output: ${JSON.stringify(trace.output).slice(0, 200)}...`;
  }

  return "";
};

/**
 * Build a dynamic system prompt that includes current view context.
 * This gives the conversational AI knowledge of what sections exist,
 * enabling it to make informed decisions about update vs generate_new actions.
 * Supports custom template with variable substitution.
 */
const buildChatSystemPrompt = (
  currentTree: ViewTree | null,
  contextData: ContextData | null | undefined,
  contextType: "trace" | "thread",
  customTemplate?: string | null,
): string => {
  const dataSummary = buildDataSummary(contextData, contextType);
  const currentViewSummary =
    currentTree?.root && currentTree.nodes[currentTree.root]
      ? summarizeViewTree(currentTree)
      : "";

  // If custom template provided, substitute variables
  if (customTemplate) {
    let prompt = customTemplate
      .replace(/\{\{data_summary\}\}/g, dataSummary)
      .replace(/\{\{context_type\}\}/g, contextType)
      .replace(
        /\{\{current_view_summary\}\}/g,
        currentViewSummary || "No custom view exists yet.",
      );

    // Add the view context section
    if (currentViewSummary) {
      prompt += `

## Current View Structure:
The user currently has a custom view with the following structure:
${currentViewSummary}

When the user wants to modify the current view (add, remove, or change sections), use action "update_existing".
When the user explicitly wants a completely new view or the current view is not relevant to their request, use action "generate_new".

Examples of "update_existing" actions:
- "Remove section 1" or "Delete the Overview section"
- "Add a metadata section" or "Include token usage"
- "Change the title of section X" or "Update the input section"

Examples of "generate_new" actions:
- "Create a new view from scratch"
- "Show me something completely different"
- "Start over with a fresh layout"`;
    } else {
      prompt += `

No custom view exists yet. When the user wants to create a view, use action "generate_new".`;
    }

    return prompt;
  }

  // Default behavior (no custom template)
  if (currentViewSummary) {
    return `${BASE_CHAT_SYSTEM_PROMPT}

${dataSummary}

## Current View Structure:
The user currently has a custom view with the following structure:
${currentViewSummary}

When the user wants to modify the current view (add, remove, or change sections), use action "update_existing".
When the user explicitly wants a completely new view or the current view is not relevant to their request, use action "generate_new".

Examples of "update_existing" actions:
- "Remove section 1" or "Delete the Overview section"
- "Add a metadata section" or "Include token usage"
- "Change the title of section X" or "Update the input section"

Examples of "generate_new" actions:
- "Create a new view from scratch"
- "Show me something completely different"
- "Start over with a fresh layout"`;
  }

  return `${BASE_CHAT_SYSTEM_PROMPT}

${dataSummary}

No custom view exists yet. When the user wants to create a view, use action "generate_new".`;
};

// Props type for content component
interface CustomViewDemoPageContentProps {
  projectId: string;
  contextType: "trace" | "thread";
  setContextType: (type: "trace" | "thread") => void;
  selectedTraceId: string | null;
  selectedThreadId: string | null;
  setSelectedTraceId: (id: string | null) => void;
  setSelectedThreadId: (id: string | null) => void;
  model: string | null;
  provider: string | null;
  setModel: (model: string | null) => void;
  setProvider: (provider: string | null) => void;
  contextData: ContextData | null | undefined;
  isContextLoading: boolean;
  isContextError: boolean;
}

const CustomViewDemoPageContent: React.FC<CustomViewDemoPageContentProps> = ({
  projectId,
  contextType,
  setContextType,
  selectedTraceId,
  selectedThreadId,
  setSelectedTraceId,
  setSelectedThreadId,
  model,
  provider,
  setModel,
  setProvider,
  contextData,
  isContextLoading,
  isContextError,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Get data-view state
  const { tree, setTree, setSource, trackPatch } = useDataView();
  const storageKey = projectId
    ? `${STORAGE_KEY_PREFIX}${projectId}`
    : undefined;
  const { save } = useViewStorage(storageKey);

  // Sync source data when contextData changes
  useEffect(() => {
    if (contextData) {
      setSource(contextDataToSourceData(contextData));
    }
  }, [contextData, setSource]);

  // Chat input state
  const [inputValue, setInputValue] = useState("");

  // View saving state
  const [isSaving, setIsSaving] = useState(false);
  const [saveVersion, setSaveVersion] = useState(0);

  // Check if current view is saved (compare only essential tree properties, ignoring metadata)
  const isSaved = useMemo(() => {
    if (!tree?.root || !projectId) return false;
    const savedTree = loadView(`${STORAGE_KEY_PREFIX}${projectId}`);
    if (!savedTree) return false;
    // Compare only version, root, and nodes - ignore metadata like timestamps
    return (
      savedTree.version === tree.version &&
      savedTree.root === tree.root &&
      JSON.stringify(savedTree.nodes) === JSON.stringify(tree.nodes)
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree, projectId, saveVersion]);

  // Check if we have a view schema (tree with root)
  const viewSchema = tree?.root ? tree : null;

  // Proposal state machine
  const [proposalState, setProposalState] = useState<ProposalState>({
    status: "idle",
  });

  // Toast for notifications
  const { toast } = useToast();

  // Custom prompts state
  const customPrompts = useCustomPrompts(projectId);

  // Dialog states for prompt configuration
  const [isConversationalDialogOpen, setIsConversationalDialogOpen] =
    useState(false);
  const [isSchemaGenerationDialogOpen, setIsSchemaGenerationDialogOpen] =
    useState(false);

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

  // Initialize ViewTree generation AI hook
  const viewTreeGenerationAI = useViewTreeGenerationAI({
    workspaceName,
  });

  // Transform conversational AI messages to display messages
  const displayMessages = useMemo<ChatDisplayMessage[]>(() => {
    return conversationalAI.messages.map((msg, index) => {
      const displayMsg: ChatDisplayMessage = {
        id: `msg-${index}`,
        role: msg.role as "user" | "assistant",
        content: msg.content,
      };

      // Include tool call data if present
      if (msg.toolCallId && msg.toolCallName && msg.toolCallArguments) {
        displayMsg.toolCall = {
          id: msg.toolCallId,
          name: msg.toolCallName,
          arguments: msg.toolCallArguments,
        };
      }

      // Include tool result status if present
      if (msg.toolResultStatus) {
        displayMsg.toolResultStatus = msg.toolResultStatus;
      }

      return displayMsg;
    });
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

    // Build dynamic system prompt that includes current view context and data
    // Use custom prompt if configured, otherwise use default
    const systemPrompt = buildChatSystemPrompt(
      viewSchema,
      contextData,
      contextType,
      customPrompts.conversationalPrompt,
    );

    const response = await conversationalAI.generate({
      model: model || "",
      userMessage,
      systemPrompt,
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
  }, [
    inputValue,
    model,
    contextData,
    contextType,
    configs,
    conversationalAI,
    viewSchema,
    customPrompts.conversationalPrompt,
  ]);

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

    const proposalId = proposalState.proposal.id;

    // Update the message that contains this tool call with "accepted" status
    conversationalAI.setMessages((prev) =>
      prev.map((msg) =>
        msg.toolCallId === proposalId
          ? { ...msg, toolResultStatus: "accepted" as const }
          : msg,
      ),
    );

    // Set to generating state
    setProposalState({
      status: "generating",
      proposal: proposalState.proposal,
    });

    // Generate ViewTree using ViewTree Generation AI with streaming callbacks
    const generatedTree = await viewTreeGenerationAI.generateViewTree({
      model: model || "",
      context: {
        intentSummary: proposalState.proposal.intentSummary,
        action: proposalState.proposal.action,
        data: contextData,
        dataType: contextType,
        model: model || "",
        currentTree: viewSchema,
      },
      configs,
      customSystemPrompt: customPrompts.schemaGenerationPrompt,
      // Progressive UI update: update tree as each patch arrives
      onPatch: (patch, tree) => {
        trackPatch(patch);
        setTree(tree);
      },
      onComplete: (tree) => {
        setTree(tree);
        toast({
          title:
            proposalState.proposal.action === "update_existing"
              ? "View updated"
              : "View generated",
          description:
            proposalState.proposal.action === "update_existing"
              ? "Custom view updated successfully"
              : "Custom view created successfully",
        });
        // Reset to idle on completion
        setProposalState({ status: "idle" });
      },
    });

    // Handle error/empty case - keep in generating state so error can show on card
    // The error will be displayed via viewTreeGenerationAI.error
    if (!generatedTree && !viewTreeGenerationAI.error) {
      // Only reset to idle if there's no error (user can retry with error state)
      setProposalState({ status: "idle" });
    }
  }, [
    proposalState,
    model,
    contextData,
    contextType,
    viewSchema,
    viewTreeGenerationAI,
    toast,
    setTree,
    trackPatch,
    configs,
    conversationalAI,
    customPrompts.schemaGenerationPrompt,
  ]);

  // Handle retrying generation after an error
  const handleRetryGeneration = useCallback(async () => {
    if (proposalState.status !== "generating") return;
    if (!model || !contextData) return;

    // Reset the tree generation AI error state
    viewTreeGenerationAI.reset();

    // Re-run generation with the same proposal
    const generatedTree = await viewTreeGenerationAI.generateViewTree({
      model: model || "",
      context: {
        intentSummary: proposalState.proposal.intentSummary,
        action: proposalState.proposal.action,
        data: contextData,
        dataType: contextType,
        model: model || "",
        currentTree: viewSchema,
      },
      configs,
      customSystemPrompt: customPrompts.schemaGenerationPrompt,
      onPatch: (patch, tree) => {
        trackPatch(patch);
        setTree(tree);
      },
      onComplete: (tree) => {
        setTree(tree);
        toast({
          title:
            proposalState.proposal.action === "update_existing"
              ? "View updated"
              : "View generated",
          description:
            proposalState.proposal.action === "update_existing"
              ? "Custom view updated successfully"
              : "Custom view created successfully",
        });
        setProposalState({ status: "idle" });
      },
    });

    if (!generatedTree && !viewTreeGenerationAI.error) {
      setProposalState({ status: "idle" });
    }
  }, [
    proposalState,
    model,
    contextData,
    contextType,
    viewSchema,
    viewTreeGenerationAI,
    toast,
    setTree,
    trackPatch,
    configs,
    customPrompts.schemaGenerationPrompt,
  ]);

  // Handle rejecting a proposal
  const handleRejectProposal = useCallback(() => {
    if (proposalState.status !== "pending") return;

    const proposalId = proposalState.proposal.id;

    // Update the message that contains this tool call with "rejected" status
    conversationalAI.setMessages((prev) =>
      prev.map((msg) =>
        msg.toolCallId === proposalId
          ? { ...msg, toolResultStatus: "rejected" as const }
          : msg,
      ),
    );

    setProposalState({ status: "idle" });
    toast({
      title: "Proposal rejected",
      description: "You can continue chatting",
    });
  }, [proposalState, toast, conversationalAI]);

  // Handle saving view schema to localStorage
  const handleSaveView = useCallback(() => {
    if (!viewSchema || !projectId) return;

    setIsSaving(true);
    try {
      save();
      // Increment saveVersion to trigger isSaved memo recalculation
      setSaveVersion((v) => v + 1);
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
  }, [viewSchema, projectId, save, toast]);

  // Handle clearing saved view
  const handleClearSaved = useCallback(() => {
    if (!projectId) return;

    deleteView(`${STORAGE_KEY_PREFIX}${projectId}`);
    setTree(createEmptyTree());
    toast({
      title: "Saved view cleared",
      description: "Saved view removed. Generate a new one or switch context.",
    });
  }, [projectId, toast, setTree]);

  return (
    <div className="flex h-[calc(100vh-var(--header-height)-var(--banner-height))] flex-col">
      <PageBodyStickyContainer className="mb-4 mt-6 px-0">
        <div className="flex items-end justify-between gap-4">
          {/* Left section: Model, Trace, Config */}
          <div className="flex items-end gap-2">
            <div className="h-full w-80">
              <label className="comet-body-s-accented mb-2 block">Model</label>
              <div className="h-8">
                <PromptModelSelect
                  value={(model as PROVIDER_MODEL_TYPE) || ""}
                  provider={provider || ""}
                  workspaceName={workspaceName}
                  onChange={(newModel, newProvider) => {
                    setModel(newModel);
                    setProvider(newProvider);
                  }}
                />
              </div>
            </div>

            <ContextSelectionSection
              projectId={projectId}
              contextType={contextType}
              selectedTraceId={selectedTraceId}
              selectedThreadId={selectedThreadId}
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
            {/* Chat AI prompt configuration button */}
            <TooltipWrapper content="Configure Chat AI system prompt">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => setIsConversationalDialogOpen(true)}
                className="relative"
              >
                <MessageSquare className="size-4" />
                {customPrompts.isConversationalCustomized && (
                  <span className="absolute -right-0.5 -top-0.5 size-2 rounded-full bg-primary" />
                )}
              </Button>
            </TooltipWrapper>

            {/* Schema Generation AI prompt configuration button */}
            <TooltipWrapper content="Configure Schema Generation AI prompt">
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={() => setIsSchemaGenerationDialogOpen(true)}
                className="relative"
              >
                <Settings className="size-4" />
                {customPrompts.isSchemaGenerationCustomized && (
                  <span className="absolute -right-0.5 -top-0.5 size-2 rounded-full bg-primary" />
                )}
              </Button>
            </TooltipWrapper>

            {/* Separator */}
            <div className="mx-1 h-6 w-px bg-border" />

            {/* Saved view indicator badge */}
            {isSaved && (
              <div className="flex items-center gap-1.5 rounded-md border border-green-200 bg-green-50 px-2.5 py-1 text-sm text-green-700 dark:border-green-800 dark:bg-green-900/20 dark:text-green-400">
                <CheckCircle2 className="size-3.5" />
                <span className="font-medium">Saved View</span>
              </div>
            )}

            {/* Unsaved indicator tag */}
            {viewSchema && !isSaved && (
              <Tag
                variant="orange"
                size="default"
                className="flex items-center gap-1.5"
              >
                <span className="size-1.5 animate-pulse rounded-full bg-current" />
                Unsaved
              </Tag>
            )}

            {/* Save button */}
            <SaveViewButton
              projectId={projectId}
              onSave={handleSaveView}
              isSaving={isSaving}
              saveVersion={saveVersion}
            />

            {/* Clear saved view button */}
            {isSaved && (
              <Button variant="ghost" size="sm" onClick={handleClearSaved}>
                <X className="mr-1 size-4" />
                Clear
              </Button>
            )}
          </div>
        </div>
      </PageBodyStickyContainer>

      {/* Info banner when using saved view */}
      {isSaved && (
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
              generationError={viewTreeGenerationAI.error}
              onRetryGeneration={handleRetryGeneration}
            />
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="data" defaultSize={60} minSize={30}>
            <CustomViewDataPanel
              isDataLoading={isContextLoading}
              isDataError={isContextError}
              isAIGenerating={proposalState.status === "generating"}
              isStreaming={viewTreeGenerationAI.isStreaming}
              patchCount={viewTreeGenerationAI.patchCount}
              isAPIError={Boolean(conversationalAI.error)}
              apiError={conversationalAI.error}
            />
          </ResizablePanel>
        </ResizablePanelGroup>
      </div>

      {/* Prompt configuration dialogs */}
      <ConversationalPromptDialog
        open={isConversationalDialogOpen}
        onOpenChange={setIsConversationalDialogOpen}
        projectId={projectId}
        currentPrompt={customPrompts.conversationalPrompt}
        onSave={customPrompts.setConversationalPrompt}
      />

      <SchemaGenerationPromptDialog
        open={isSchemaGenerationDialogOpen}
        onOpenChange={setIsSchemaGenerationDialogOpen}
        projectId={projectId}
        currentPrompt={customPrompts.schemaGenerationPrompt}
        onSave={customPrompts.setSchemaGenerationPrompt}
      />
    </div>
  );
};

// Wrapper component that provides DataViewProvider
const CustomViewDemoPage: React.FC = () => {
  const projectId = useProjectIdFromURL();
  const params = useCustomViewParams();
  const { contextData, isLoading, isError } = useCustomViewData({
    projectId,
    contextType: params.contextType,
    traceId: params.selectedTraceId,
    threadId: params.selectedThreadId,
  });

  const sourceData = useMemo(
    () => (contextData ? contextDataToSourceData(contextData) : {}),
    [contextData],
  );

  const storageKey = projectId
    ? `${STORAGE_KEY_PREFIX}${projectId}`
    : undefined;

  return (
    <DataViewProvider initialSource={sourceData} storageKey={storageKey}>
      <CustomViewDemoPageContent
        projectId={projectId}
        contextType={params.contextType}
        setContextType={params.setContextType}
        selectedTraceId={params.selectedTraceId}
        selectedThreadId={params.selectedThreadId}
        setSelectedTraceId={params.setSelectedTraceId}
        setSelectedThreadId={params.setSelectedThreadId}
        model={params.model}
        provider={params.provider}
        setModel={params.setModel}
        setProvider={params.setProvider}
        contextData={contextData}
        isContextLoading={isLoading}
        isContextError={isError}
      />
    </DataViewProvider>
  );
};

export default CustomViewDemoPage;
