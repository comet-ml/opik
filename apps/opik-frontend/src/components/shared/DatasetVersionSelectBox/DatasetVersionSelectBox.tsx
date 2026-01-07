import React, { useMemo, useRef, useState } from "react";
import {
  Select,
  SelectContent,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Database, Plus, X, Search, GitCommitVertical } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import useDatasetVersionSelect, {
  DEFAULT_LOADED_DATASETS,
} from "./useDatasetVersionSelect";
import DatasetOption from "./DatasetOption";
import VersionOption from "./VersionOption";
import { Dataset } from "@/types/datasets";
import {
  parseDatasetVersionKey,
  formatDatasetVersionKey,
} from "@/utils/datasetVersionStorage";

export interface DatasetVersionData {
  hash: string;
  name: string;
}

interface DatasetVersionSelectBoxProps {
  value: string | null; // "datasetId::versionId" format
  versionName?: string;
  onChange: (value: string | null) => void;
  workspaceName: string;
  disabled?: boolean;
  showClearButton?: boolean;
  buttonClassName?: string;
}

const DatasetEmptyState = () => {
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

const DatasetVersionSelectBox: React.FC<DatasetVersionSelectBoxProps> = ({
  value,
  versionName,
  onChange,
  workspaceName,
  disabled = false,
  showClearButton = true,
  buttonClassName,
}) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const resetDialogKeyRef = useRef(0);

  const [search, setSearch] = useState("");
  const [openDatasetId, setOpenDatasetId] = useState<string | null>(null);
  const [isSelectOpen, setIsSelectOpen] = useState(false);
  const [isDialogOpen, setIsDialogOpen] = useState(false);

  // Parse value to extract datasetId for internal use
  const parsed = parseDatasetVersionKey(value);
  const datasetId = parsed?.datasetId || null;

  const {
    datasets,
    isLoadingDatasets,
    versions,
    isLoadingVersions,
    filteredDatasets,
    loadMore,
    hasMore,
  } = useDatasetVersionSelect({
    workspaceName,
    search,
    openDatasetId,
    selectedDatasetId: datasetId,
  });

  const selectedDataset = useMemo(
    () => datasets.find((d) => d.id === datasetId),
    [datasets, datasetId],
  );

  const displayValue = useMemo(() => {
    if (!selectedDataset) return null;

    return `${selectedDataset.name} / ${versionName || ""}`;
  }, [selectedDataset, versionName]);

  const handleDatasetCreated = async (newDataset: Dataset) => {
    // TODO remove ! after BE migration
    const latestVersion = newDataset.latest_version!;

    const formattedValue = formatDatasetVersionKey(
      newDataset.id,
      latestVersion.id,
    );
    onChange(formattedValue);
    setIsDialogOpen(false);
  };

  const renderNestedList = () => {
    return filteredDatasets.map((dataset) => {
      const isOpen = dataset.id === openDatasetId;
      const isSelected = dataset.id === datasetId;

      return (
        <Popover key={dataset.id} open={isOpen}>
          <PopoverTrigger asChild>
            <DatasetOption
              dataset={dataset}
              workspaceName={workspaceName}
              isSelected={isSelected}
              isOpen={isOpen}
              onMouseEnter={() => setOpenDatasetId(dataset.id)}
              onMouseLeave={() => setOpenDatasetId(null)}
            />
          </PopoverTrigger>

          <PopoverContent
            side="right"
            align="start"
            className="max-h-[400px] overflow-y-auto p-1"
            onMouseEnter={() => setOpenDatasetId(dataset.id)}
            hideWhenDetached
          >
            {isLoadingVersions ? (
              <div className="flex items-center justify-center py-4">
                <Spinner />
              </div>
            ) : versions.length === 0 ? (
              <div className="comet-body-s flex min-w-40 items-center justify-center py-2 text-muted-slate">
                No versions
              </div>
            ) : (
              versions.map((version) => (
                <VersionOption
                  key={version.id}
                  version={version}
                  datasetId={dataset.id}
                  isSelected={parsed?.versionId === version.id}
                />
              ))
            )}
          </PopoverContent>
        </Popover>
      );
    });
  };

  const renderOptions = () => {
    if (isLoadingDatasets) {
      return (
        <div className="flex items-center justify-center py-4">
          <Spinner />
        </div>
      );
    }

    if (filteredDatasets.length === 0) {
      if (search) {
        return (
          <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
            No search results
          </div>
        );
      }
      return <DatasetEmptyState />;
    }

    return (
      <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
        {renderNestedList()}
        {hasMore && (
          <>
            <SelectSeparator />
            <div className="flex items-center justify-between border-t border-border px-4 py-2">
              <div className="comet-body-s text-light-slate">
                Showing first {DEFAULT_LOADED_DATASETS} items.
              </div>
              <Button variant="link" onClick={loadMore} type="button">
                Load more
              </Button>
            </div>
          </>
        )}
      </div>
    );
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key.length === 1) {
      event.preventDefault();
      setSearch((prev) => prev + event.key);
    }
    inputRef.current?.focus();
  };

  return (
    <>
      <div className="flex">
        <Select
          value={value || ""}
          onValueChange={(selectedValue) => {
            onChange(selectedValue);
            setIsSelectOpen(false);
            setOpenDatasetId(null);
          }}
          onOpenChange={(open) => {
            if (!open) {
              setSearch("");
              setOpenDatasetId(null);
            }
            setIsSelectOpen(open);
          }}
          open={isSelectOpen}
          disabled={disabled}
        >
          <TooltipWrapper content={displayValue || "Select a dataset"}>
            <SelectTrigger
              className={cn(
                "size-full w-[220px] data-[placeholder]:text-light-slate h-[32px] py-0",
                {
                  "rounded-r-none": !!value && showClearButton,
                },
                buttonClassName,
              )}
            >
              <SelectValue
                placeholder={
                  <div className="flex w-full items-center text-light-slate">
                    <Database className="mr-2 size-4" />
                    <span className="truncate font-normal">
                      Select a dataset
                    </span>
                  </div>
                }
              >
                <div className="flex w-full items-center gap-2 text-foreground">
                  <Database className="size-4 shrink-0" />

                  <div className="flex min-w-0 items-center gap-1.5 font-medium text-foreground">
                    <span className="truncate">{selectedDataset?.name}</span>
                    <GitCommitVertical className="size-3.5 shrink-0 text-muted-slate" />
                    <span className="truncate">{versionName || ""}</span>
                  </div>
                </div>
              </SelectValue>
            </SelectTrigger>
          </TooltipWrapper>

          <SelectContent
            onKeyDown={handleKeyDown}
            className="max-h-[700px] p-0"
          >
            <div className="flex h-full flex-col">
              <div className="relative flex h-10 items-center justify-center gap-1 pl-6">
                <Search className="absolute left-2 size-4 text-light-slate" />
                <Input
                  ref={inputRef}
                  className="outline-0"
                  placeholder="Search datasets"
                  value={search}
                  variant="ghost"
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>
              <SelectSeparator />
              {renderOptions()}

              <Separator className="my-1" />
              <div
                className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
                onClick={() => {
                  setIsSelectOpen(false);
                  setIsDialogOpen(true);
                }}
              >
                <div className="comet-body-s flex items-center gap-2 text-primary">
                  <Plus className="size-3.5 shrink-0" />
                  <span>Create a new dataset</span>
                </div>
              </div>
            </div>
          </SelectContent>
        </Select>

        {value && showClearButton && (
          <Button
            variant="outline"
            size="icon-sm"
            className="rounded-l-none border-l-0"
            onClick={() => onChange(null)}
            disabled={disabled}
          >
            <X className="text-light-slate" />
          </Button>
        )}
      </div>
      <AddEditDatasetDialog
        key={resetDialogKeyRef.current}
        open={isDialogOpen}
        setOpen={setIsDialogOpen}
        onDatasetCreated={handleDatasetCreated}
        csvRequired
      />
    </>
  );
};

export default DatasetVersionSelectBox;
