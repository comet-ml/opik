import { useCallback, useMemo, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Database, Pause, Pencil, Play, RotateCcw, X } from "lucide-react";

import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RunOnDatasetDialog from "@/v2/pages/PlaygroundPage/RunOnDatasetDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Link } from "@tanstack/react-router";
import { generateDefaultPrompt } from "@/lib/playground";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { Filters } from "@/types/filters";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";
import {
  usePromptMap,
  useClearRunningMap,
  useSetPromptMap,
  useClearCreatedExperiments,
  useIsRunning,
  useSelectedRuleIds,
  useSetSelectedRuleIds,
  useResetDatasetFilters,
  useResetOutputMap,
  useSetDatasetVariables,
  useSetDatasetFilters,
  useSetExperimentNamePrefix,
  useDatasetFilters,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  supportsImageInput,
  supportsVideoInput,
} from "@/lib/modelCapabilities";
import { hasImagesInContent, hasVideosInContent } from "@/lib/llm";

interface PlaygroundHeaderProps {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  datasetId: string | null;
  datasetName: string | null;
  versionName?: string;
  onChangeDatasetId: (id: string | null) => void;
  onRunAll: () => void;
  onDeferredRunAll: () => void;
  onStopAll: () => void;
  maxWidth?: string;
}

const PlaygroundHeader = ({
  workspaceName,
  providerKeys,
  datasetId,
  datasetName,
  versionName,
  onChangeDatasetId,
  onRunAll,
  onDeferredRunAll,
  onStopAll,
  maxWidth,
}: PlaygroundHeaderProps) => {
  const promptMap = usePromptMap();
  const setPromptMap = useSetPromptMap();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const selectedRuleIds = useSelectedRuleIds();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const clearRunningMap = useClearRunningMap();
  const resetDatasetFilters = useResetDatasetFilters();
  const resetOutputMap = useResetOutputMap();
  const setDatasetVariables = useSetDatasetVariables();
  const setDatasetFilters = useSetDatasetFilters();
  const setExperimentNamePrefix = useSetExperimentNamePrefix();
  const isRunning = useIsRunning();
  const filters = useDatasetFilters();

  const resetKeyRef = useRef(0);
  const leaveKeyRef = useRef(0);
  const [resetDialogOpen, setResetDialogOpen] = useState(false);
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);
  const [runOnDatasetOpen, setRunOnDatasetOpen] = useState(false);

  const {
    permissions: { canViewExperiments, canViewDatasets },
  } = usePermissions();

  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [, setLocalStorageDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    { defaultValue: null },
  );
  const [, setDatasetVersionKey] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    { defaultValue: null },
  );

  const isExperimentMode = !!datasetId;

  const hasMediaCompatibilityIssues = useMemo(() => {
    return Object.values(promptMap).some((prompt) => {
      if (!prompt.model) return false;
      const modelSupportsImages = supportsImageInput(prompt.model);
      const modelSupportsVideos = supportsVideoInput(prompt.model);
      const hasImages = prompt.messages.some((message) =>
        hasImagesInContent(message.content),
      );
      const hasVideos = prompt.messages.some((message) =>
        hasVideosInContent(message.content),
      );
      return (
        (hasImages && !modelSupportsImages) ||
        (hasVideos && !modelSupportsVideos)
      );
    });
  }, [promptMap]);

  const allPromptsHaveModels = useMemo(
    () => Object.values(promptMap).every((p) => !!p.model),
    [promptMap],
  );

  const allMessagesNotEmpty = useMemo(
    () =>
      Object.values(promptMap).every((p) =>
        p.messages.every((m) => m.content?.length > 0),
      ),
    [promptMap],
  );

  const isRunDisabled =
    !allPromptsHaveModels ||
    !allMessagesNotEmpty ||
    hasMediaCompatibilityIssues;

  const runDisabledReason = useMemo(() => {
    if (!allPromptsHaveModels)
      return "Please select an LLM model for your prompts";
    if (!allMessagesNotEmpty)
      return "Some messages are empty. Please add some text to proceed";
    if (hasMediaCompatibilityIssues)
      return "Some prompts contain media but the selected model doesn't support media input";
    return null;
  }, [allPromptsHaveModels, allMessagesNotEmpty, hasMediaCompatibilityIssues]);

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    setLocalStorageDatasetId(null);
    setDatasetVersionKey(null);
    onChangeDatasetId(null);
    setSelectedRuleIds(null);
    clearCreatedExperiments();
    clearRunningMap();
    resetDatasetFilters();
    setDatasetVariables([]);
    setExperimentNamePrefix(null);
  }, [
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
    setLocalStorageDatasetId,
    setDatasetVersionKey,
    onChangeDatasetId,
    setSelectedRuleIds,
    clearCreatedExperiments,
    clearRunningMap,
    resetDatasetFilters,
    setDatasetVariables,
    setExperimentNamePrefix,
  ]);

  const handleLeaveExperimentMode = useCallback(() => {
    clearCreatedExperiments();
    resetOutputMap();
    onChangeDatasetId(null);
    setLocalStorageDatasetId(null);
    setDatasetVersionKey(null);
    resetDatasetFilters();
    setSelectedRuleIds(null);
    setExperimentNamePrefix(null);
  }, [
    clearCreatedExperiments,
    resetOutputMap,
    onChangeDatasetId,
    setLocalStorageDatasetId,
    setDatasetVersionKey,
    resetDatasetFilters,
    setSelectedRuleIds,
    setExperimentNamePrefix,
  ]);

  const handleRunOnDataset = useCallback(
    (params: {
      datasetId: string;
      versionId?: string;
      datasetName: string;
      selectedRuleIds: string[] | null;
      experimentNamePrefix: string;
      filters: Filters;
    }) => {
      if (params.versionId) {
        setDatasetVersionKey(`${params.datasetId}::${params.versionId}`);
        onChangeDatasetId(`${params.datasetId}::${params.versionId}`);
      } else {
        setLocalStorageDatasetId(params.datasetId);
        onChangeDatasetId(params.datasetId);
      }

      setSelectedRuleIds(params.selectedRuleIds);
      setDatasetFilters(params.filters);
      setExperimentNamePrefix(params.experimentNamePrefix);
      onDeferredRunAll();
    },
    [
      setDatasetVersionKey,
      setLocalStorageDatasetId,
      onChangeDatasetId,
      setSelectedRuleIds,
      setDatasetFilters,
      setExperimentNamePrefix,
      onDeferredRunAll,
    ],
  );

  const renderExperimentChipOrButton = () => {
    if (!canViewDatasets) return null;

    if (isExperimentMode) {
      const chipLabel = versionName
        ? `${datasetName} ${versionName}`
        : datasetName;

      return (
        <div className="flex h-7 items-center rounded-md border bg-background">
          <button
            className="flex items-center gap-1.5 px-2 text-muted-slate hover:text-primary-hover"
            onClick={() => setRunOnDatasetOpen(true)}
          >
            <Database className="size-4 shrink-0 text-[#b8e54a]" />
            <span className="comet-body-s max-w-[200px] truncate">
              {chipLabel}
            </span>
            <Pencil className="size-3.5 shrink-0" />
          </button>
          <Separator orientation="vertical" className="h-full" />
          <button
            className="flex items-center p-1.5 text-muted-slate hover:text-primary-hover"
            onClick={() => {
              leaveKeyRef.current += 1;
              setLeaveDialogOpen(true);
            }}
          >
            <X className="size-3.5" />
          </button>
        </div>
      );
    }

    return (
      <TooltipWrapper content={isRunDisabled ? runDisabledReason : undefined}>
        <Button
          variant="outline"
          size="xs"
          onClick={() => setRunOnDatasetOpen(true)}
          disabled={isRunDisabled}
          style={isRunDisabled ? { pointerEvents: "auto" } : {}}
        >
          <Database className="mr-1 size-4" />
          Test on dataset
        </Button>
      </TooltipWrapper>
    );
  };

  const renderRunButton = () => {
    if (isRunning) {
      return (
        <TooltipWrapper content="Stop running prompts">
          <Button size="xs" variant="outline" onClick={onStopAll}>
            <Pause className="mr-1 size-4" />
            Stop all
          </Button>
        </TooltipWrapper>
      );
    }

    const label = isExperimentMode ? "Re-run" : "Run all";
    const tooltip =
      runDisabledReason ??
      (isExperimentMode
        ? "Re-run experiment with same dataset and metrics"
        : "Run your prompts");

    return (
      <TooltipWrapper content={tooltip}>
        <Button
          size="xs"
          onClick={() => onRunAll()}
          disabled={isRunDisabled}
          style={isRunDisabled ? { pointerEvents: "auto" } : {}}
        >
          <Play className="mr-1 size-4" />
          {label}
          <HotkeyDisplay hotkey="⇧" size="xs" className="ml-1.5" />
          <HotkeyDisplay hotkey="⏎" size="xs" className="ml-1" />
        </Button>
      </TooltipWrapper>
    );
  };

  const leaveDescription = canViewExperiments ? (
    <>
      You&apos;ll return to prompt iteration mode. Your experiment results are
      saved and can be viewed anytime on the{" "}
      <Link
        to="/$workspaceName/experiments"
        params={{ workspaceName }}
        target="_blank"
        className="underline"
      >
        Experiments
      </Link>{" "}
      page.
    </>
  ) : (
    "You'll return to prompt iteration mode."
  );

  return (
    <>
      <div
        className="flex items-center justify-between px-4 py-3"
        style={maxWidth ? { maxWidth } : undefined}
      >
        <h1 className="comet-title-s">Playground</h1>
        <div className="flex items-center gap-2">
          {renderExperimentChipOrButton()}
          {renderRunButton()}
          <Button
            variant="ghost"
            size="xs"
            onClick={() => {
              resetKeyRef.current += 1;
              setResetDialogOpen(true);
            }}
          >
            <RotateCcw className="mr-2 size-4" />
            Reset
          </Button>
        </div>
      </div>

      <ConfirmDialog
        key={`reset-${resetKeyRef.current}`}
        open={resetDialogOpen}
        setOpen={setResetDialogOpen}
        onConfirm={resetPlayground}
        title="Reset playground"
        description="Resetting the Playground will discard all unsaved prompts. This action can't be undone. Are you sure you want to continue?"
        confirmText="Reset playground"
      />

      <ConfirmDialog
        key={`leave-${leaveKeyRef.current}`}
        open={leaveDialogOpen}
        setOpen={setLeaveDialogOpen}
        onConfirm={handleLeaveExperimentMode}
        title="Leave experiment mode?"
        description={leaveDescription}
        confirmText="Leave"
      />

      <RunOnDatasetDialog
        open={runOnDatasetOpen}
        onClose={() => setRunOnDatasetOpen(false)}
        onRun={handleRunOnDataset}
        workspaceName={workspaceName}
        initialDatasetId={datasetId}
        initialSelectedRuleIds={selectedRuleIds}
        initialFilters={filters}
      />
    </>
  );
};

export default PlaygroundHeader;
