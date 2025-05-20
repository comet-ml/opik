import React, { useCallback, useEffect, useMemo, useState } from "react";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset, DatasetItem } from "@/types/datasets";
import { Button } from "@/components/ui/button";
import { Database, Pause, Play, X } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

import {
  usePromptCount,
  usePromptMap,
  useResetOutputMap,
} from "@/store/PlaygroundStore";

import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useActionButtonActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/useActionButtonActions";
import { cn } from "@/lib/utils";

const EMPTY_DATASETS: Dataset[] = [];

interface PlaygroundOutputActionsProps {
  datasetId: string | null;
  onChangeDatasetId: (id: string | null) => void;
  workspaceName: string;
  datasetItems: DatasetItem[];
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
  loadingDatasetItems,
}: PlaygroundOutputActionsProps) => {
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  const promptMap = usePromptMap();
  const promptCount = usePromptCount();
  const resetOutputMap = useResetOutputMap();

  const { data: datasetsData, isLoading: isLoadingDatasets } = useDatasetsList({
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

  const { stopAll, runAll, isRunning } = useActionButtonActions({
    workspaceName,
    datasetItems,
    datasetName,
  });

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

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
      isDatasetEmpty;

    const shouldTooltipAppear = !!(
      !allPromptsHaveModels ||
      !allMessagesNotEmpty ||
      isDatasetEmpty ||
      isDatasetRemoved
    );

    const style: React.CSSProperties = isDisabledButton
      ? { pointerEvents: "auto" }
      : {};

    const getTooltipMessage = () => {
      if (!isDisabledButton) {
        return promptCount === 1 ? "Run your prompt" : "Run your prompts";
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
    <div className="sticky right-0 ml-auto flex h-0 gap-2">
      <div className="mt-2.5 flex">
        <LoadableSelectBox
          options={datasetOptions}
          value={datasetId || ""}
          placeholder={
            <div className="flex w-full items-center text-light-slate">
              <Database className="mr-2 size-4" />
              <span className="truncate font-normal">Test over dataset</span>
            </div>
          }
          onChange={handleChangeDatasetId}
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

      {renderActionButton()}
    </div>
  );
};

export default PlaygroundOutputActions;
