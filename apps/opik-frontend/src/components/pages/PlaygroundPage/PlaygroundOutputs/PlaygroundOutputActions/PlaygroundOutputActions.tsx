import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Pause, Play } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import AddEditRuleDialog from "@/components/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import MetricSelector from "./MetricSelector";
import { DatasetVersionSelectBox } from "@/components/shared/DatasetVersionSelectBox";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";
import PlaygroundProgressIndicator from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundProgressIndicator";
import NavigationTag from "@/components/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { generateExperimentIdFilter } from "@/lib/filters";

import useDatasetsList from "@/api/datasets/useDatasetsList";
import useProjectByName from "@/api/projects/useProjectByName";
import useRulesList from "@/api/automations/useRulesList";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import {
  usePromptCount,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useSetSelectedRuleIds,
} from "@/store/PlaygroundStore";
import useActionButtonActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/useActionButtonActions";
import {
  supportsImageInput,
  supportsVideoInput,
} from "@/lib/modelCapabilities";
import { hasImagesInContent, hasVideosInContent } from "@/lib/llm";

import { Dataset, DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { COLUMN_DATA_ID, COLUMN_TYPE } from "@/types/shared";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import DatasetSelectBox from "@/components/pages-shared/llm/DatasetSelectBox/DatasetSelectBox";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { usePermissions } from "@/contexts/PermissionsContext";

const EMPTY_DATASETS: Dataset[] = [];
const DEFAULT_LOADED_DATASETS = 1000;

interface PlaygroundOutputActionsProps {
  datasetId: string | null;
  versionName?: string;
  onChangeDatasetId: (id: string | null) => void;
  workspaceName: string;
  datasetItems: DatasetItem[];
  datasetColumns: DatasetItemColumn[];
  loadingDatasetItems: boolean;
  filters: Filters;
  onFiltersChange: (filters: Filters) => void;
  page: number;
  onChangePage: (page: number) => void;
  size: number;
  onChangeSize: (size: number) => void;
  total: number;
  isLoadingTotal?: boolean;
}

const RUN_HOT_KEYS = ["⌘", "⏎"];

const PlaygroundOutputActions = ({
  datasetId,
  versionName,
  onChangeDatasetId,
  workspaceName,
  datasetItems,
  datasetColumns,
  loadingDatasetItems,
  filters,
  onFiltersChange,
  page,
  onChangePage,
  size,
  onChangeSize,
  total,
  isLoadingTotal,
}: PlaygroundOutputActionsProps) => {
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);
  const [ruleDialogProjectId, setRuleDialogProjectId] = useState<
    string | undefined
  >(undefined);
  const isVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
  );

  const promptMap = usePromptMap();
  const promptCount = usePromptCount();
  const resetOutputMap = useResetOutputMap();
  const selectedRuleIds = useSelectedRuleIds();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const queryClient = useQueryClient();
  const createProjectMutation = useProjectCreateMutation();

  const { canViewExperiments } = usePermissions();

  // Define filters column data - includes all dataset columns and tags
  const filtersColumnData = useMemo(() => {
    // Add each data column as a separate filter option with field prefix "data."
    // This will be transformed to field="data" and key=columnName when processing
    const dataFilterColumns = datasetColumns.map((c) => ({
      id: `${COLUMN_DATA_ID}.${c.name}`,
      label: c.name,
      type: COLUMN_TYPE.string,
    }));

    return [
      ...dataFilterColumns,
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags" as const,
      },
    ];
  }, [datasetColumns]);

  // Fetch playground project - always fetch to show metric selector
  const {
    data: playgroundProject,
    isError: isProjectError,
    error: projectError,
    isLoading: isLoadingProject,
  } = useProjectByName(
    {
      projectName: PLAYGROUND_PROJECT_NAME,
    },
    {
      enabled: !!workspaceName,
      retry: false,
    },
  );

  // Check if error is a 404 (project not found)
  const isProjectNotFound =
    isProjectError &&
    projectError &&
    "response" in projectError &&
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (projectError as any).response?.status === 404;

  // Fetch automation rules for playground project - always fetch to show metric selector
  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: playgroundProject?.id,
      page: 1,
      size: 100,
    },
    {
      enabled: !!playgroundProject?.id,
    },
  );

  const rules = rulesData?.content || [];

  const { data: datasetsData, isLoading: isLoadingDatasets } = useDatasetsList({
    workspaceName,
    page: 1,
    size: DEFAULT_LOADED_DATASETS,
  });

  const datasets = datasetsData?.content || EMPTY_DATASETS;
  // Parse datasetId to extract plain ID (handles both "id" and "id::hash" formats)
  const parsedDatasetId = parseDatasetVersionKey(datasetId);
  const plainDatasetId = parsedDatasetId?.datasetId || datasetId;
  const datasetName =
    datasets?.find((ds) => ds.id === plainDatasetId)?.name || null;

  const { stopAll, runAll, isRunning, createdExperiments } =
    useActionButtonActions({
      workspaceName,
      datasetItems,
      datasetName,
      datasetId: plainDatasetId || undefined,
      datasetVersionId: parsedDatasetId?.versionId || undefined,
    });

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

  const handleDatasetChangeExtra = useCallback(() => {
    resetOutputMap();
    stopAll();
  }, [resetOutputMap, stopAll]);

  const handleDatasetVersionChange = useCallback(
    (value: string | null) => {
      onChangeDatasetId(value);
      handleDatasetChangeExtra();
    },
    [onChangeDatasetId, handleDatasetChangeExtra],
  );

  const handleCreateRuleClick = useCallback(async () => {
    try {
      let projectId: string | undefined = playgroundProject?.id;

      // If project is still loading, wait a bit (shouldn't normally happen, but just in case)
      if (isLoadingProject) {
        // Wait a moment and try to get the project again
        await new Promise((resolve) => setTimeout(resolve, 500));
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        projectId = (playgroundProject as any)?.id;
      }

      // If project doesn't exist (404), create it
      if (!projectId && isProjectNotFound) {
        const result = await createProjectMutation.mutateAsync({
          project: { name: PLAYGROUND_PROJECT_NAME },
        });
        projectId = result.id;

        // Refetch the project query to get the updated project data
        await queryClient.refetchQueries({
          queryKey: ["project", { projectName: PLAYGROUND_PROJECT_NAME }],
        });
      }

      // Set the project ID for the dialog and open it
      if (projectId || playgroundProject?.id) {
        setRuleDialogProjectId(projectId || playgroundProject?.id);
        setIsRuleDialogOpen(true);
      }
    } catch (error) {
      // Error handling is done by the mutation hook (toast notification)
      console.error("Failed to create playground project:", error);
    }
  }, [
    playgroundProject,
    isProjectNotFound,
    isLoadingProject,
    createProjectMutation,
    queryClient,
  ]);

  const renderActionButton = () => {
    if (isRunning) {
      const stopRunningPromptMessage =
        promptCount === 1 ? "Stop a running prompt" : "Stop running prompts";

      return (
        <TooltipWrapper
          content={stopRunningPromptMessage}
          hotkeys={RUN_HOT_KEYS}
        >
          <Button
            size="sm"
            className="mt-2.5"
            variant="outline"
            onClick={stopAll}
          >
            <Pause className="mr-1 size-4" />
            Stop all
          </Button>
        </TooltipWrapper>
      );
    }

    const allPromptsHaveModels = Object.values(promptMap).every(
      (p) => !!p.model,
    );
    const allMessagesNotEmpty = Object.values(promptMap).every((p) =>
      p.messages.every((m) => m.content?.length > 0),
    );
    const isDatasetEmpty =
      !loadingDatasetItems && !!plainDatasetId && datasetItems.length === 0;

    const isDatasetRemoved =
      !isLoadingDatasets &&
      plainDatasetId &&
      !datasets.find((d) => d.id === plainDatasetId);

    const isDisabledButton =
      !allPromptsHaveModels ||
      !allMessagesNotEmpty ||
      loadingDatasetItems ||
      isLoadingDatasets ||
      isDatasetRemoved ||
      isDatasetEmpty ||
      hasMediaCompatibilityIssues;

    const shouldTooltipAppear =
      !allPromptsHaveModels ||
      !allMessagesNotEmpty ||
      isDatasetEmpty ||
      isDatasetRemoved ||
      hasMediaCompatibilityIssues;
    const style: React.CSSProperties = isDisabledButton
      ? { pointerEvents: "auto" }
      : {};

    const getTooltipMessage = () => {
      if (!isDisabledButton) {
        return promptCount === 1 ? "Run your prompt" : "Run your prompts";
      }

      if (hasMediaCompatibilityIssues) {
        return "Some prompts contain media but the selected model doesn't support media input. Please change the model or remove media from the messages";
      }

      if (isDatasetRemoved) {
        return "Your dataset has been removed. Select another one";
      }

      if (isDatasetEmpty) {
        return "Selected dataset is empty";
      }

      if (!allPromptsHaveModels) {
        return promptCount === 1
          ? "Please select an LLM model for your prompt"
          : "Please select an LLM model for your prompts";
      }

      if (!allMessagesNotEmpty) {
        return "Some messages are empty. Please add some text to proceed";
      }

      return "Action is disabled";
    };

    const tooltipKey = shouldTooltipAppear
      ? "action-tooltip-open-tooltip"
      : "action-tooltip";

    const hasActiveFilters = filters.length > 0;
    const isPaginationActive = page > 1 || size < total;
    const isSubsetSelected = hasActiveFilters || isPaginationActive;

    const runLabel = isSubsetSelected
      ? "Run selection"
      : promptCount > 1 || (datasetId && datasetItems.length > 1)
        ? "Run all"
        : "Run";

    return (
      <TooltipWrapper
        content={getTooltipMessage()}
        key={tooltipKey}
        defaultOpen={shouldTooltipAppear}
        hotkeys={isDisabledButton ? undefined : RUN_HOT_KEYS}
      >
        <Button
          size="sm"
          className="mt-2.5"
          onClick={runAll}
          disabled={isDisabledButton}
          style={style}
        >
          <Play className="mr-1 size-4" />
          {runLabel}
        </Button>
      </TooltipWrapper>
    );
  };

  // Set default to "all selected" when dataset is selected and rules are available
  useEffect(() => {
    if (!datasetId) {
      // Reset to null when dataset is deselected
      setSelectedRuleIds(null);
    }
    // Note: We don't automatically normalize [] to null because [] is a valid state
    // meaning "none selected". Users should be able to explicitly deselect all items.
    // null = all selected (default), [] = none selected, [id1, id2] = specific rules selected
  }, [datasetId, setSelectedRuleIds]);

  useEffect(() => {
    // stop streaming whenever a user leaves a page
    return () => stopAll();
  }, [stopAll]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
        event.preventDefault();
        event.stopPropagation();

        if (isRunning) {
          stopAll();
        } else {
          runAll();
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown, true);

    return () => {
      window.removeEventListener("keydown", handleKeyDown, true);
    };
  }, [runAll, isRunning, stopAll]);

  return (
    <>
      {isRunning && datasetId && (
        <div className="mb-4 mt-2">
          <PlaygroundProgressIndicator />
        </div>
      )}
      <div className="sticky flex items-center justify-between gap-2">
        {createdExperiments.length > 0 && plainDatasetId && (
          <div className="flex gap-2">
            {canViewExperiments && (
              <div className="mt-2.5">
                <NavigationTag
                  resource={RESOURCE_TYPE.experiment}
                  id={plainDatasetId}
                  name={
                    createdExperiments.length === 1
                      ? "Experiment"
                      : "Experiments"
                  }
                  className="h-8"
                  search={{
                    experiments: createdExperiments.map((e) => e.id),
                  }}
                  tooltipContent={
                    createdExperiments.length === 1
                      ? "Your run was stored in this experiment. Explore your results to find insights."
                      : "Your run was stored in experiments. Explore comparison results to get insights."
                  }
                />
              </div>
            )}
            {createdExperiments.length === 1 &&
              playgroundProject?.id &&
              createdExperiments[0]?.id && (
                <div className="mt-2.5">
                  <NavigationTag
                    resource={RESOURCE_TYPE.traces}
                    id={playgroundProject.id}
                    name="Traces"
                    className="h-8"
                    search={{
                      traces_filters: generateExperimentIdFilter(
                        createdExperiments[0].id,
                      ),
                    }}
                    tooltipContent="View all traces for this experiment"
                  />
                </div>
              )}
          </div>
        )}
        <div className="ml-auto flex gap-2">
          <div className="mt-2.5">
            {isVersioningEnabled ? (
              <DatasetVersionSelectBox
                value={datasetId}
                versionName={versionName}
                onChange={handleDatasetVersionChange}
                workspaceName={workspaceName}
              />
            ) : (
              <DatasetSelectBox
                value={datasetId ?? ""}
                onChange={onChangeDatasetId}
                workspaceName={workspaceName}
                onDatasetChangeExtra={handleDatasetChangeExtra}
              />
            )}
          </div>
          {datasetId && (
            <div className="mt-2.5 flex">
              <FiltersButton
                columns={filtersColumnData}
                filters={filters}
                onChange={onFiltersChange}
                layout="icon"
              />
            </div>
          )}
          <div className="mt-2.5 flex">
            <MetricSelector
              rules={rules}
              selectedRuleIds={selectedRuleIds}
              onSelectionChange={setSelectedRuleIds}
              datasetId={datasetId}
              onCreateRuleClick={handleCreateRuleClick}
              workspaceName={workspaceName}
            />
          </div>
          {datasetId && (
            <div className="mt-2.5 flex h-8 items-center justify-center">
              <Separator orientation="vertical" className="mr-2 h-4" />
              <DataTablePagination
                page={page}
                pageChange={onChangePage}
                size={size}
                sizeChange={onChangeSize}
                total={total}
                variant="minimal"
                itemsPerPage={[10, 50, 100, 200, 500, 1000]}
                disabled={isRunning}
                isLoadingTotal={isLoadingTotal}
              />
              <Separator orientation="vertical" className="mx-2 h-4" />
            </div>
          )}
          <div className="-ml-0.5 mt-2.5 flex h-8 items-center gap-2">
            <ExplainerIcon
              {...EXPLAINERS_MAP[EXPLAINER_ID.what_does_the_dataset_do_here]}
            />
            <Separator orientation="vertical" className="mx-2 h-4" />
          </div>
          {renderActionButton()}
        </div>
      </div>
      <AddEditRuleDialog
        open={isRuleDialogOpen}
        setOpen={(open) => {
          setIsRuleDialogOpen(open);
          if (!open) {
            // Reset project ID when dialog closes
            setRuleDialogProjectId(undefined);
          }
        }}
        projectId={ruleDialogProjectId || playgroundProject?.id}
        projectName={PLAYGROUND_PROJECT_NAME}
        datasetColumnNames={datasetColumns.map((c) => c.name)}
        hideScopeSelector
      />
    </>
  );
};

export default PlaygroundOutputActions;
