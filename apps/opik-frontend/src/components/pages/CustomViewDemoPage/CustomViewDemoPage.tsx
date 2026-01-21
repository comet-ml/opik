import React, { useState, useMemo, useCallback, useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
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
import TraceSelectionSection from "./TraceSelectionSection";
import CustomViewChatPanel from "./CustomViewChatPanel";
import CustomViewDataPanel from "./CustomViewDataPanel";
import useStructuredCompletion from "@/api/playground/useStructuredCompletion";
import { useChatScroll } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIViewer/useChatScroll";
import { ChatDisplayMessage } from "@/types/structured-completion";
import { safelyParseJSON } from "@/lib/utils";
import {
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
} from "@/types/providers";
import { STRUCTURED_OUTPUT_SUPPORTED_MODELS } from "@/constants/llm";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  customViewZodSchema,
  CustomViewSchema,
  ViewSource,
} from "@/types/custom-view";
import useTraceById from "@/api/traces/useTraceById";
import { customViewStorage } from "@/lib/customViewStorage";
import { useToast } from "@/components/ui/use-toast";
import SaveViewButton from "./SaveViewButton";
import { Save, X, CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";

// System prompt to guide AI in generating custom view schemas
const SYSTEM_PROMPT = `You are an AI assistant that analyzes LLM trace data and creates custom visualization schemas.

Your task is to examine the trace data structure and identify the most important and relevant fields to display to the user.

## Available Widget Types:
- **text**: Plain text values (strings, general content)
- **number**: Numeric values (counts, metrics, scores)
- **boolean**: True/false values
- **code**: Code snippets, JSON objects, formatted data
- **link**: URLs that should be clickable
- **image**: Image URLs (jpg, png, gif, webp, svg)
- **video**: Video URLs (mp4, webm, mov)
- **audio**: Audio URLs (mp3, wav, m4a)
- **pdf**: PDF file URLs
- **file**: Generic file attachments

## Widget Sizes (REQUIRED):
Each widget MUST have a \`size\` field to control its layout width:
- **small**: For compact data (numbers, booleans, short text ≤50 chars, links)
  - Takes 1/3 width on desktop, allowing 3 widgets per row
- **medium**: For standard content (text, images, audio)
  - Takes 1/2 width on desktop, allowing 2 widgets per row
- **large**: For rich content (code blocks, JSON, video, PDFs)
  - Takes 2/3 width on desktop, allowing 1.5 widgets per row
- **full**: For special cases requiring full width
  - Takes full width on all screen sizes

## Path Format:
- Use dot notation for nested objects: \`input.messages\`, \`output.result\`
- Use array indexing for array items: \`messages[0].content\`, \`messages[1].content\`
- **IMPORTANT**: For arrays, list each item separately with its index, not the array itself
  - ✅ GOOD: \`messages[0].content\`, \`messages[1].content\`, \`messages[2].content\`
  - ❌ BAD: \`messages\` (don't reference the array directly)

## Guidelines:
1. Identify the 4-8 most important fields in the trace
2. Choose the appropriate widget type AND size for each field
3. **Sort widgets by size**: Place small widgets first, then medium, then large/full
   - This creates better layouts with less empty space
   - Small widgets fill gaps efficiently
4. Provide clear, descriptive labels
5. For arrays, create separate widgets for each item (up to 5 items)
6. Prioritize user-facing content (inputs, outputs, messages) over metadata
7. Include key metrics if available (usage, cost, duration)
8. Group related fields together when possible

## Size Selection Examples:
- \`usage.total_tokens\` → number widget, size: "small"
- \`input.messages[0].content\` → text widget, size: "medium"
- \`output.result\` → code widget, size: "large"
- \`metadata.image_url\` → image widget, size: "medium"
- \`is_successful\` → boolean widget, size: "small"

## Response Format:
Return a JSON object with:
- \`responseSummary\`: A brief explanation of what you've identified
- \`widgets\`: An array of widget configurations (sorted by size: small → medium → large → full)

The full trace data is provided below:
{{trace}}`;

const CustomViewDemoPage: React.FC = () => {
  const projectId = useProjectIdFromURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [selectedTraceId, setSelectedTraceId] = useQueryParam(
    "traceId",
    StringParam,
  );
  const [model, setModel] = useQueryParam("model", StringParam);
  const [provider, setProvider] = useQueryParam("provider", StringParam);

  // Chat input state
  const [inputValue, setInputValue] = useState("");

  // View schema state - persists across trace changes
  const [viewSchema, setViewSchema] = useState<CustomViewSchema | null>(null);

  // View source tracking
  const [viewSource, setViewSource] = useState<ViewSource>("empty");
  const [isSaving, setIsSaving] = useState(false);

  // Toast for notifications
  const { toast } = useToast();

  // Model configuration state
  const [configs, setConfigs] = useState<Partial<LLMPromptConfigsType>>(() =>
    getDefaultConfigByProvider(
      (provider as COMPOSED_PROVIDER_TYPE) || "",
      (model as PROVIDER_MODEL_TYPE) || "",
    ),
  );

  // Fetch trace data
  const {
    data: traceData,
    isPending: isTraceLoading,
    isError: isTraceError,
  } = useTraceById(
    { traceId: selectedTraceId || "", stripAttachments: false },
    { enabled: Boolean(selectedTraceId) },
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

  // Load saved schema on mount or when projectId changes
  useEffect(() => {
    if (!projectId) return;

    const savedSchema = customViewStorage.load(projectId);
    if (savedSchema) {
      setViewSchema(savedSchema);
      setViewSource("saved");
    } else {
      // Reset to empty when switching to project without saved view
      if (viewSource !== "ai") {
        setViewSchema(null);
        setViewSource("empty");
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]); // Only depend on projectId

  // Initialize structured completion hook with custom view schema
  const { messages, isLoading, generate, result, error } =
    useStructuredCompletion({
      schema: customViewZodSchema,
      workspaceName,
    });

  // Store last request parameters for retry functionality
  const [lastRequestParams, setLastRequestParams] = useState<{
    model: string;
    userMessage: string;
    systemPrompt: string;
    context: { trace: unknown };
    configs: Partial<LLMPromptConfigsType>;
  } | null>(null);

  // Update view schema when AI generates a new result
  useEffect(() => {
    if (result) {
      setViewSchema(result as CustomViewSchema);
      setViewSource("ai"); // Mark as AI-generated
    }
  }, [result]);

  // Transform structured completion messages to display messages
  const displayMessages = useMemo<ChatDisplayMessage[]>(() => {
    return messages.map((msg, index) => {
      if (msg.role === "user") {
        return {
          id: `msg-${index}`,
          role: "user" as const,
          content: msg.content,
        };
      }
      // Assistant messages contain JSON string - extract responseSummary
      const parsed = safelyParseJSON(msg.content);
      return {
        id: `msg-${index}`,
        role: "assistant" as const,
        content: parsed?.responseSummary || msg.content,
        isError: !parsed?.responseSummary,
      };
    });
  }, [messages]);

  // Add loading message if currently loading
  const displayMessagesWithLoading = useMemo<ChatDisplayMessage[]>(() => {
    if (isLoading && displayMessages.length > 0) {
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
  }, [displayMessages, isLoading]);

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
    isStreaming: isLoading,
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
    if (!inputValue.trim() || !model || !selectedTraceId || !traceData) return;

    const userMessage = inputValue;
    setInputValue("");

    const requestParams = {
      model: model || "",
      userMessage,
      systemPrompt: SYSTEM_PROMPT,
      context: {
        trace: traceData,
      },
      configs,
    };

    setLastRequestParams(requestParams);
    await generate(requestParams);
  }, [inputValue, model, selectedTraceId, traceData, configs, generate]);

  // Handle retry - resend the last failed request
  const handleRetry = useCallback(async () => {
    if (!lastRequestParams) return;
    await generate(lastRequestParams);
  }, [lastRequestParams, generate]);

  // Handle input change
  const handleInputChange = useCallback((value: string) => {
    setInputValue(value);
  }, []);

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
  }, [viewSchema, projectId, toast]);

  // Handle clearing saved view
  const handleClearSaved = useCallback(() => {
    if (!projectId) return;

    customViewStorage.delete(projectId);
    setViewSchema(null);
    setViewSource("empty");
    toast({
      title: "Saved view cleared",
      description: "Saved view removed. Generate a new one or switch traces.",
    });
  }, [projectId, toast]);

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

            <TraceSelectionSection
              projectId={projectId}
              selectedTraceId={selectedTraceId}
              onSelectTrace={setSelectedTraceId}
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
              isLoading={isLoading}
              error={error}
              onInputChange={handleInputChange}
              onSend={handleSend}
              onRetry={handleRetry}
              scrollContainerRef={scrollContainerRef}
              model={model}
              traceId={selectedTraceId}
            />
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="data" defaultSize={60} minSize={30}>
            <CustomViewDataPanel
              trace={traceData}
              viewSchema={viewSchema}
              isTraceLoading={isTraceLoading && Boolean(selectedTraceId)}
              isTraceError={isTraceError}
              isAIGenerating={isLoading}
              isAPIError={Boolean(error)}
              apiError={error}
            />
          </ResizablePanel>
        </ResizablePanelGroup>
      </div>
    </div>
  );
};

export default CustomViewDemoPage;
