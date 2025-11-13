import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Database, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset } from "@/types/datasets";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_DATASETS = 1000;
const MAX_LOADED_DATASETS = 10000;

export const DatasetEmptyState = () => {
  return (
    <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
      <div className="comet-body-s-accented pb-1 text-foreground">
        No datasets available
      </div>
      <div className="comet-body-s text-muted-slate">
        Create a dataset with examples to evaluate your prompt on.
      </div>
    </div>
  );
};

interface LLMDatasetSelectBoxProps {
  value: string | null;
  onChange: (id: string | null) => void;
  disabled?: boolean;
  showClearButton?: boolean;
  buttonClassName?: string;
}

const LLMDatasetSelectBox: React.FC<LLMDatasetSelectBoxProps> = ({
  value,
  onChange,
  disabled = false,
  showClearButton = true,
  buttonClassName,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [isDatasetDialogOpen, setIsDatasetDialogOpen] = useState(false);
  const [isDatasetDropdownOpen, setIsDatasetDropdownOpen] = useState(false);
  const queryClient = useQueryClient();

  const {
    data: datasetsData,
    isLoading: isLoadingDatasets,
    isFetching: isFetchingDatasets,
  } = useDatasetsList({
    workspaceName,
    page: 1,
    size: !isLoadedMore ? DEFAULT_LOADED_DATASETS : MAX_LOADED_DATASETS,
  });

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );
  const datasetTotal = datasetsData?.total;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const datasetOptions = useMemo(() => {
    return datasets.map((ds) => ({
      label: ds.name,
      value: ds.id,
    }));
  }, [datasets]);

  useEffect(() => {
    if (value && !isLoadingDatasets && !isFetchingDatasets) {
      const datasetExists = datasets.some((ds) => ds.id === value);
      if (!datasetExists) {
        onChange(null);
      }
    }
  }, [value, datasets, isLoadingDatasets, isFetchingDatasets, onChange]);

  const handleChangeDatasetId = useCallback(
    (id: string | null) => {
      if (value !== id) {
        onChange(id);
      }
    },
    [onChange, value],
  );

  const handleDatasetCreated = useCallback(
    (newDataset: Dataset) => {
      queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
      if (newDataset.id) {
        handleChangeDatasetId(newDataset.id);
      }
      setIsDatasetDialogOpen(false);
    },
    [queryClient, handleChangeDatasetId],
  );

  return (
    <>
      <div className="flex">
        <LoadableSelectBox
          options={datasetOptions}
          value={value || ""}
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
          buttonClassName={cn(
            "w-[310px]",
            {
              "rounded-r-none": !!value && showClearButton,
            },
            buttonClassName,
          )}
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
          disabled={disabled}
        />

        {value && showClearButton && (
          <Button
            variant="outline"
            size="icon-sm"
            className="rounded-l-none border-l-0"
            onClick={() => handleChangeDatasetId(null)}
            disabled={disabled}
          >
            <X className="text-light-slate" />
          </Button>
        )}
      </div>
      <AddEditDatasetDialog
        open={isDatasetDialogOpen}
        setOpen={setIsDatasetDialogOpen}
        onDatasetCreated={handleDatasetCreated}
        csvRequired
      />
    </>
  );
};

export default LLMDatasetSelectBox;
