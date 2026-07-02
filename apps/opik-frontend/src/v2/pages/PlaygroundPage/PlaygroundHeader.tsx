import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Pause, Play, RotateCcw } from "lucide-react";

import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import RunExperimentControl from "@/v2/pages/PlaygroundPage/RunExperimentControl";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { generateDefaultPrompt } from "@/lib/playground";
import { LOGS_SOURCE } from "@/types/traces";
import { useActiveProjectId } from "@/store/AppStore";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { DATASET_TYPE } from "@/types/datasets";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import {
  buildDatasetFilterColumns,
  transformDataColumnFilters,
} from "@/lib/filters";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";
import {
  usePromptMap,
  useSetPromptMap,
  useClearCreatedExperiments,
  useCreatedExperiments,
  useIsRunning,
  useSetSelectedRuleIds,
  useResetDatasetFilters,
  useResetOutputMap,
  useSetExperimentNamePrefix,
  useSetDatasetType,
  useDatasetType,
  useDatasetFilters,
  useSetDatasetFilters,
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
  onReset: () => void;
  onRunAll: () => void;

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
  onReset,
  onRunAll,
  onStopAll,
  maxWidth,
}: PlaygroundHeaderProps) => {
  const promptMap = usePromptMap();
  const setPromptMap = useSetPromptMap();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const createdExperiments = useCreatedExperiments();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const resetDatasetFilters = useResetDatasetFilters();
  const resetOutputMap = useResetOutputMap();
  const setExperimentNamePrefix = useSetExperimentNamePrefix();
  const isRunning = useIsRunning();
  const setDatasetType = useSetDatasetType();
  const currentDatasetType = useDatasetType();
  const filters = useDatasetFilters();
  const setDatasetFilters = useSetDatasetFilters();

  const resetKeyRef = useRef(0);
  const [resetDialogOpen, setResetDialogOpen] = useState(false);

  const {
    permissions: { canViewExperiments, canCreateExperiments, canViewDatasets },
  } = usePermissions();

  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const isExperimentMode = !!datasetId;
  const activeProjectId = useActiveProjectId();
  const isDatasetExperiment =
    isExperimentMode && currentDatasetType === DATASET_TYPE.DATASET;

  const parsedDatasetKey = useMemo(
    () => parseDatasetVersionKey(datasetId),
    [datasetId],
  );
  const plainDatasetId = parsedDatasetKey?.datasetId ?? datasetId;

  const { data: filterVersionsData } = useDatasetVersionsList(
    { datasetId: plainDatasetId!, page: 1, size: 1000 },
    { enabled: !!plainDatasetId && isDatasetExperiment },
  );
  const filterVersionHash = useMemo(
    () =>
      filterVersionsData?.content?.find(
        (v) => v.id === parsedDatasetKey?.versionId,
      )?.version_hash,
    [filterVersionsData?.content, parsedDatasetKey?.versionId],
  );

  const { data: datasetItemsForColumns } = useDatasetItemsList(
    {
      datasetId: plainDatasetId!,
      page: 1,
      size: 1,
      truncate: true,
      filters: filters.length ? transformDataColumnFilters(filters) : undefined,
      versionId: filterVersionHash,
    },
    { enabled: !!plainDatasetId && isDatasetExperiment },
  );

  const filterColumns = useMemo(
    () => buildDatasetFilterColumns(datasetItemsForColumns?.columns ?? []),
    [datasetItemsForColumns?.columns],
  );

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
    hasMediaCompatibilityIssues ||
    (isExperimentMode && !datasetName);

  const runDisabledReason = useMemo(() => {
    if (!allPromptsHaveModels)
      return "Please select an LLM model for your prompts";
    if (!allMessagesNotEmpty)
      return "Some messages are empty. Please add some text to proceed";
    if (hasMediaCompatibilityIssues)
      return "Some prompts contain media but the selected model doesn't support media input";
    if (isExperimentMode && !datasetName)
      return "Your dataset has been removed. Select another one";
    return null;
  }, [
    allPromptsHaveModels,
    allMessagesNotEmpty,
    hasMediaCompatibilityIssues,
    isExperimentMode,
    datasetName,
  ]);

  // Keyboard shortcut: Shift+Enter to run all
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (
        event.shiftKey &&
        event.key === "Enter" &&
        !isRunDisabled &&
        !isRunning
      ) {
        event.preventDefault();
        event.stopPropagation();
        onRunAll();
      }
    };
    window.addEventListener("keydown", handleKeyDown, true);
    return () => window.removeEventListener("keydown", handleKeyDown, true);
  }, [onRunAll, isRunDisabled, isRunning]);

  const resetPlayground = useCallback(() => {
    onReset();
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
  }, [
    onReset,
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
  ]);

  const handleLeaveExperimentMode = useCallback(() => {
    clearCreatedExperiments();
    resetOutputMap();
    onChangeDatasetId(null);
    resetDatasetFilters();
    setSelectedRuleIds(null);
    setExperimentNamePrefix(null);
    setDatasetType(null);
  }, [
    clearCreatedExperiments,
    resetOutputMap,
    onChangeDatasetId,
    resetDatasetFilters,
    setSelectedRuleIds,
    setExperimentNamePrefix,
    setDatasetType,
  ]);

  const renderExperimentControl = () => {
    if (!(canViewDatasets && canCreateExperiments && canViewExperiments))
      return null;

    return (
      <>
        <RunExperimentControl
          workspaceName={workspaceName}
          datasetId={datasetId}
          versionName={versionName}
          onChangeDatasetId={onChangeDatasetId}
          onLeaveExperimentMode={handleLeaveExperimentMode}
        />
        {isDatasetExperiment && (
          <FiltersButton
            columns={filterColumns}
            filters={filters}
            onChange={setDatasetFilters}
            layout="icon"
            variant="outline"
            size="icon-2xs"
            deferOnChange
          />
        )}
      </>
    );
  };

  const renderRunButton = () => {
    if (isRunning) {
      return (
        <TooltipWrapper content="Stop running prompts">
          <Button size="2xs" variant="outline" onClick={onStopAll}>
            <Pause className="mr-1 size-3.5" />
            Stop all
          </Button>
        </TooltipWrapper>
      );
    }

    const hasRunExperiment = isExperimentMode && createdExperiments.length > 0;
    const label = hasRunExperiment ? "Re-run" : "Run";
    const experimentTarget =
      currentDatasetType === DATASET_TYPE.TEST_SUITE ? "test suite" : "dataset";
    const tooltip =
      runDisabledReason ??
      (isExperimentMode
        ? `Run experiment on ${experimentTarget}`
        : "Run your prompts");

    return (
      <TooltipWrapper content={tooltip}>
        <Button
          data-testid="playground-run-button"
          data-mode={hasRunExperiment ? "re-run" : "run"}
          size="2xs"
          onClick={() => onRunAll()}
          disabled={isRunDisabled}
          style={isRunDisabled ? { pointerEvents: "auto" } : {}}
        >
          <Play className="mr-1 size-3" />
          {label}
          <HotkeyDisplay hotkey="⇧" size="2xs" className="ml-1.5" />
          <HotkeyDisplay hotkey="⏎" size="2xs" className="ml-1" />
        </Button>
      </TooltipWrapper>
    );
  };

  return (
    <>
      <div
        className="flex items-center justify-between px-4 py-3"
        style={maxWidth ? { maxWidth } : undefined}
      >
        <div className="flex items-center gap-2">
          <h1 className="comet-title-xs">Playground</h1>
        </div>
        <div className="flex items-center gap-2">
          {renderExperimentControl()}
          {renderRunButton()}
          <Separator orientation="vertical" className="h-5" />
          <TooltipWrapper content="Reset playground">
            <Button
              variant="outline"
              size="icon-2xs"
              onClick={() => {
                resetKeyRef.current += 1;
                setResetDialogOpen(true);
              }}
            >
              <RotateCcw />
            </Button>
          </TooltipWrapper>
          {activeProjectId && (
            <TraceLogsSidebarButton
              projectId={activeProjectId}
              logsSource={LOGS_SOURCE.playground}
              variant="icon"
              title="Playground logs"
            />
          )}
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
    </>
  );
};

export default PlaygroundHeader;
