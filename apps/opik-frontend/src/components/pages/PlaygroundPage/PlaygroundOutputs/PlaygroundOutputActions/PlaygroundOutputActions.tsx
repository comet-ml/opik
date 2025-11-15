import React, { useCallback, useEffect, useMemo, useState } from "react";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset, DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { Button } from "@/components/ui/button";
import { Database, FlaskConical, Pause, Play, Plus, X } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

import {
  usePromptCount,
  usePromptMap,
  useResetOutputMap,
  useSelectedRuleIds,
  useSetSelectedRuleIds,
} from "@/store/PlaygroundStore";
import useProjectByName from "@/api/projects/useProjectByName";
import useRulesList from "@/api/automations/useRulesList";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import MetricSelector from "./MetricSelector";
import AddEditRuleDialog from "@/components/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import { useQueryClient } from "@tanstack/react-query";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useActionButtonActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/useActionButtonActions";
import { cn } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { Separator } from "@/components/ui/separator";
import {
  supportsImageInput,
  supportsVideoInput,
} from "@/lib/modelCapabilities";
import { hasImagesInContent, hasVideosInContent } from "@/lib/llm";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import DatasetEmptyState from "./DatasetEmptyState";

const EMPTY_DATASETS: Dataset[] = [];

interface PlaygroundOutputActionsProps {
  datasetId: string | null;
  onChangeDatasetId: (id: string | null) => void;
  workspaceName: string;
  datasetItems: DatasetItem[];
  datasetColumns: DatasetItemColumn[];
  loadingDatasetItems: boolean;
}

const DEFAULT_LOADED_DATASETS = 1000;
const MAX_LOADED_DATASETS = 10000;

const RUN_HOT_KEYS = ["⌘", "⏎"];

const PlaygroundOutputActions = ({
  datasetId,
  onChangeDatasetId,
  workspaceName,
  datasetItems,
  datasetColumns,
  loadingDatasetItems,
}: PlaygroundOutputActionsProps) => {
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);
  const [isDatasetDialogOpen, setIsDatasetDialogOpen] = useState(false);
  const [isDatasetDropdownOpen, setIsDatasetDropdownOpen] = useState(false);
  const [ruleDialogProjectId, setRuleDialogProjectId] = useState<
    string | undefined
  >(undefined);

  const promptMap = usePromptMap();
  const promptCount = usePromptCount();
  const resetOutputMap = useResetOutputMap();
  const selectedRuleIds = useSelectedRuleIds();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const queryClient = useQueryClient();
  const createProjectMutation = useProjectCreateMutation();

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

  const {
    data: datasetsData,
    isLoading: isLoadingDatasets,
    isFetching: isFetchingDatasets,
  } = useDatasetsList({
    workspaceName,
    page: 1,
    size: !isLoadedMore ? DEFAULT_LOADED_DATASETS : MAX_LOADED_DATASETS,
  });

  const datasets = datasetsData?.content || EMPTY_DATASETS;
  const datasetTotal = datasetsData?.total;

  const datasetOptions = useMemo(() => {
    return datasets.map((ds) => ({
      label: ds.name,
      value: ds.id,
    }));
  }, [datasets]);

  const datasetName = datasets?.find((ds) => ds.id === datasetId)?.name || null;

  // Clear datasetId if the selected dataset no longer exists
  useEffect(() => {
    if (datasetId && !isLoadingDatasets && !isFetchingDatasets) {
      const datasetExists = datasets.some((ds) => ds.id === datasetId);
      if (!datasetExists) {
        onChangeDatasetId(null);
      }
    }
  }, [
    datasetId,
    datasets,
    isLoadingDatasets,
    isFetchingDatasets,
    onChangeDatasetId,
  ]);

  const {
    stopAll,
    runAll,
    isRunning,
    createdExperiments,
    navigateToExperiments,
  } = useActionButtonActions({
    workspaceName,
    datasetItems,
    datasetName,
    datasetId: datasetId ? datasetId : undefined,
  });

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

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

  const handleChangeDatasetId = useCallback(
    (id: string | null) => {
      if (datasetId !== id) {
        onChangeDatasetId(id);
        resetOutputMap();
        stopAll();
      }
    },
    [onChangeDatasetId, resetOutputMap, stopAll, datasetId],
  );

  const handleDatasetCreated = useCallback(
    (newDataset: Dataset) => {
      // Invalidate datasets query to refresh the list
      queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
      // Select the newly created dataset
      if (newDataset.id) {
        handleChangeDatasetId(newDataset.id);
      }
      setIsDatasetDialogOpen(false);
    },
    [queryClient, handleChangeDatasetId],
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
      !loadingDatasetItems && !!datasetId && datasetItems.length === 0;

    const isDatasetRemoved =
      !isLoadingDatasets &&
      datasetId &&
      !datasets.find((d) => d.id === datasetId);

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

    const runLabel =
      promptCount > 1 || (datasetId && datasetItems.length > 1)
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
      <div className="sticky right-0 ml-auto flex h-0 gap-2">
        {createdExperiments.length > 0 && (
          <TooltipWrapper
            content={
              createdExperiments.length === 1
                ? "Your run was stored in this experiment. Explore your results to find insights."
                : "Your run was stored in experiments. Explore comparison results to get insights."
            }
          >
            <Button
              size="sm"
              className="mt-2.5"
              onClick={navigateToExperiments}
              variant="outline"
            >
              <FlaskConical className="mr-2 size-4" />
              {createdExperiments.length === 1
                ? "Explore experiment"
                : "Compare experiments"}
            </Button>
          </TooltipWrapper>
        )}
        <div className="mt-2.5 flex">
          <LoadableSelectBox
            options={datasetOptions}
            value={datasetId || ""}
            placeholder={
              <div className="flex w-full items-center text-light-slate">
                <Database className="mr-2 size-4" />
                <span className="truncate font-normal">Select a dataset</span>
              </div>
            }
            onChange={handleChangeDatasetId}
            open={isDatasetDropdownOpen}
            onOpenChange={setIsDatasetDropdownOpen}
            buttonSize="sm"
            onLoadMore={
              (datasetTotal || 0) > DEFAULT_LOADED_DATASETS && !isLoadedMore
                ? loadMoreHandler
                : undefined
            }
            isLoading={isLoadingDatasets}
            optionsCount={DEFAULT_LOADED_DATASETS}
            buttonClassName={cn("w-[310px]", {
              "rounded-r-none": !!datasetId,
            })}
            renderTitle={(option) => {
              return (
                <div className="flex w-full items-center text-foreground">
                  <Database className="mr-2 size-4" />
                  <span className="max-w-[90%] truncate">{option.label}</span>
                </div>
              );
            }}
            emptyState={<DatasetEmptyState />}
            actionPanel={
              <div className="sticky inset-x-0 bottom-0">
                <Separator className="my-1" />
                <div
                  className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
                  onClick={() => {
                    setIsDatasetDropdownOpen(false);
                    setIsDatasetDialogOpen(true);
                  }}
                >
                  <div className="comet-body-s flex items-center gap-2 text-primary">
                    <Plus className="size-3.5 shrink-0" />
                    <span>Create a new dataset</span>
                  </div>
                </div>
              </div>
            }
          />

          {datasetId && (
            <Button
              variant="outline"
              size="icon-sm"
              className="rounded-l-none border-l-0 "
              onClick={() => handleChangeDatasetId(null)}
            >
              <X className="text-light-slate" />
            </Button>
          )}
        </div>
        <div className="mt-2.5 flex">
          <MetricSelector
            rules={rules}
            selectedRuleIds={selectedRuleIds}
            onSelectionChange={setSelectedRuleIds}
            datasetId={datasetId}
            onCreateRuleClick={handleCreateRuleClick}
          />
        </div>
        <div className="-ml-0.5 mt-2.5 flex h-8 items-center gap-2">
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.what_does_the_dataset_do_here]}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
        </div>
        {renderActionButton()}
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
      <AddEditDatasetDialog
        open={isDatasetDialogOpen}
        setOpen={setIsDatasetDialogOpen}
        onDatasetCreated={handleDatasetCreated}
        csvRequired
      />
    </>
  );
};

export default PlaygroundOutputActions;
