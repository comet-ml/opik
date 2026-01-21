import React, { useCallback, useEffect, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import PlaygroundPrompt from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import {
  generateDefaultPrompt,
  getDefaultConfigByProvider,
} from "@/lib/playground";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { Button } from "@/components/ui/button";
import { Plus, RotateCcw } from "lucide-react";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_SELECTED_DATASET_KEY,
} from "@/constants/llm";
import {
  useAddPrompt,
  usePromptCount,
  usePromptIds,
  useSetIsRunning,
  useSetPromptMap,
  useClearCreatedExperiments,
  useSetSelectedRuleIds,
  useResetDatasetFilters,
  useSetDatasetVariables,
  useTraceContext,
  useSetTraceContext,
  useClearTraceContext,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { getAndClearPlaygroundPrefill } from "@/hooks/useOpenInPlayground";
import { useToast } from "@/components/ui/use-toast";
import { PlaygroundPromptType } from "@/types/playground";
import TraceContextSidebar from "@/components/pages/PlaygroundPage/TraceContextSidebar";
import { PlaygroundTraceContext } from "@/lib/playground/extractPlaygroundData";

interface PlaygroundPromptsState {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  onResetHeight: () => void;
  hasDataset: boolean;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
  onResetHeight,
  hasDataset,
}: PlaygroundPromptsState) => {
  const promptCount = usePromptCount();
  const addPrompt = useAddPrompt();
  const setPromptMap = useSetPromptMap();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const setIsRunning = useSetIsRunning();
  const resetDatasetFilters = useResetDatasetFilters();
  const setDatasetVariables = useSetDatasetVariables();
  const traceContext = useTraceContext();
  const setTraceContext = useSetTraceContext();
  const clearTraceContext = useClearTraceContext();
  const resetKeyRef = useRef(0);
  const scrollToPromptRef = useRef<string>("");
  const [open, setOpen] = useState<boolean>(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(false);
  const prefillCheckedRef = useRef(false);
  const { toast } = useToast();

  const promptIds = usePromptIds();
  const [lastPickedModel, setLastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
    scrollToPromptRef.current = newPrompt.id;
  };

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    setDatasetId(null);
    setSelectedRuleIds(null);
    clearCreatedExperiments();
    setIsRunning(false);
    resetDatasetFilters();
    setDatasetVariables([]);
    clearTraceContext(); // Clear trace context when resetting
    onResetHeight();
  }, [
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
    setDatasetId,
    setSelectedRuleIds,
    clearCreatedExperiments,
    setIsRunning,
    resetDatasetFilters,
    setDatasetVariables,
    clearTraceContext,
    onResetHeight,
  ]);

  // Load prefill data from trace/span if available
  const loadPrefillData = useCallback((): {
    prompt: PlaygroundPromptType;
    traceContext: PlaygroundTraceContext | null;
  } | null => {
    const prefillData = getAndClearPlaygroundPrefill();
    if (!prefillData || prefillData.messages.length === 0) {
      return null;
    }

    // Try to resolve the model from the prefill data
    let model: PROVIDER_MODEL_TYPE | "" = "";
    let provider: COMPOSED_PROVIDER_TYPE | "" = "";

    if (prefillData.model) {
      // Try to find the exact model in available providers
      const resolvedModel = calculateDefaultModel(
        prefillData.model as PROVIDER_MODEL_TYPE,
        providerKeys,
      );
      if (resolvedModel) {
        model = resolvedModel;
        provider = calculateModelProvider(model);
      }
    }

    // If no model resolved but we have a provider from the trace, try to use it
    if (!model && prefillData.provider) {
      const traceProvider = prefillData.provider as COMPOSED_PROVIDER_TYPE;
      // Check if the trace provider is available in user's configured providers
      if (providerKeys.includes(traceProvider)) {
        // Get a default model for this provider
        model = calculateDefaultModel("", providerKeys, traceProvider);
        if (model) {
          provider = traceProvider;
        }
      }
    }

    // Final fallback: use the last picked model or default
    if (!model) {
      model = calculateDefaultModel(lastPickedModel || "", providerKeys);
      provider = calculateModelProvider(model);
    }

    // Generate prompt name based on trace context
    const promptName = prefillData.traceContext?.sourceSpan
      ? `From: ${prefillData.traceContext.sourceSpan.name}`
      : prefillData.traceContext?.traceName
        ? `From: ${prefillData.traceContext.traceName}`
        : "Prompt from Trace";

    // Create the prompt with prefill data
    const newPrompt: PlaygroundPromptType = {
      id: `prefill-${Date.now()}`,
      name: promptName,
      messages: prefillData.messages,
      model,
      provider,
      configs: getDefaultConfigByProvider(provider, model),
    };

    return {
      prompt: newPrompt,
      traceContext: prefillData.traceContext || null,
    };
  }, [
    calculateDefaultModel,
    calculateModelProvider,
    providerKeys,
    lastPickedModel,
  ]);

  // Check for prefill data on mount
  useEffect(() => {
    if (prefillCheckedRef.current || isPendingProviderKeys) {
      return;
    }

    prefillCheckedRef.current = true;
    const prefillResult = loadPrefillData();

    if (prefillResult) {
      const { prompt: prefillPrompt, traceContext: newTraceContext } =
        prefillResult;

      // Reset playground state, then set up with the prefilled prompt
      setPromptMap([prefillPrompt.id], { [prefillPrompt.id]: prefillPrompt });
      setDatasetId(null);
      setSelectedRuleIds(null);
      clearCreatedExperiments();
      setIsRunning(false);
      resetDatasetFilters();
      setDatasetVariables([]);
      onResetHeight();

      // Set trace context if available
      if (newTraceContext) {
        setTraceContext(newTraceContext);
        setSidebarCollapsed(false); // Show sidebar when loading from trace
      }

      // Update last picked model if we have one
      if (prefillPrompt.model) {
        setLastPickedModel(prefillPrompt.model);
      }

      // Build toast message with trace context info
      const sourceName = newTraceContext?.sourceSpan?.name
        ? `"${newTraceContext.sourceSpan.name}"`
        : newTraceContext?.traceName
          ? `trace "${newTraceContext.traceName}"`
          : "your trace";

      toast({
        title: "Trace loaded in Playground",
        description: `Messages from ${sourceName} have been loaded. You can now modify and re-run them.`,
      });
    }
  }, [
    isPendingProviderKeys,
    loadPrefillData,
    setPromptMap,
    setDatasetId,
    setSelectedRuleIds,
    clearCreatedExperiments,
    setIsRunning,
    resetDatasetFilters,
    setDatasetVariables,
    onResetHeight,
    setLastPickedModel,
    setTraceContext,
    toast,
  ]);

  useEffect(() => {
    // hasn't been initialized yet or the last prompt is removed
    if (promptCount === 0 && !isPendingProviderKeys) {
      resetPlayground();
    }
  }, [promptCount, isPendingProviderKeys, resetPlayground]);

  return (
    <div className="flex h-full">
      {/* Trace Context Sidebar */}
      {traceContext && (
        <TraceContextSidebar
          traceContext={traceContext}
          workspaceName={workspaceName}
          onClose={clearTraceContext}
          collapsed={sidebarCollapsed}
          onToggleCollapse={() => setSidebarCollapsed(!sidebarCollapsed)}
        />
      )}

      {/* Main Playground Content */}
      <div
        className={`flex min-w-0 flex-1 flex-col ${traceContext ? "pl-6" : ""}`}
      >
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-1">
            <h1 className="comet-title-l">Playground</h1>
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_playground]}
            />
          </div>

          <div className="sticky right-0 flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setOpen(true);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
            >
              <RotateCcw className="mr-2 size-4" />
              Reset playground
            </Button>

            <Button variant="outline" size="sm" onClick={handleAddPrompt}>
              <Plus className="mr-2 size-4" />
              Add prompt
            </Button>
          </div>
        </div>

        <div
          className={`flex size-full gap-[var(--item-gap)] ${
            hasDataset ? "h-auto min-h-0 flex-1 overflow-x-auto" : ""
          }`}
        >
          {promptIds.map((promptId, idx) => (
            <PlaygroundPrompt
              workspaceName={workspaceName}
              promptId={promptId}
              index={idx}
              key={promptId}
              providerKeys={providerKeys}
              isPendingProviderKeys={isPendingProviderKeys}
              providerResolver={calculateModelProvider}
              modelResolver={calculateDefaultModel}
              scrollToPromptRef={scrollToPromptRef}
            />
          ))}
        </div>
        <ConfirmDialog
          key={resetKeyRef.current}
          open={Boolean(open)}
          setOpen={setOpen}
          onConfirm={resetPlayground}
          title="Reset playground"
          description="Resetting the Playground will discard all unsaved prompts. This action can't be undone. Are you sure you want to continue?"
          confirmText="Reset playground"
        />
      </div>
    </div>
  );
};

export default PlaygroundPrompts;
