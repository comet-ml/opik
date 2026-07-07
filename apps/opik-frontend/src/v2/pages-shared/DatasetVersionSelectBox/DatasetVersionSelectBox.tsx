import { KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { Select, SelectContent, SelectTrigger, SelectValue } from "@/ui/select";
import { Button } from "@/ui/button";
import {
  ChevronRight,
  Database,
  GitCommitVertical,
  Info,
  ListChecks,
  Plus,
} from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import { Spinner } from "@/ui/spinner";
import { cn } from "@/lib/utils";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditTestSuiteDialog from "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import useDatasetVersionSelect, {
  DEFAULT_LOADED_DATASETS,
} from "./useDatasetVersionSelect";
import VersionOption from "./VersionOption";
import { useFetchDataset } from "@/api/datasets/useDatasetById";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { usePermissions } from "@/contexts/PermissionsContext";
import AddEditDatasetDialog from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog";
import DropdownEmptyState from "@/v2/pages-shared/DropdownEmptyState/DropdownEmptyState";
import {
  parseDatasetVersionKey,
  formatDatasetVersionKey,
} from "@/utils/datasetVersionStorage";
import emptyDatasetOrSuiteLightUrl from "/images/empty-dataset-or-suite-light.svg";
import emptyDatasetOrSuiteDarkUrl from "/images/empty-dataset-or-suite-dark.svg";

export interface DatasetVersionData {
  hash: string;
  name: string;
}

interface DatasetVersionSelectBoxProps {
  value: string | null; // "datasetId::versionId" format
  versionName?: string;
  onChange: (value: string | null) => void;
  projectId?: string | null;
  datasetType?: DATASET_TYPE;
  autoOpen?: boolean;
  onDismiss?: () => void;
}

function DatasetVersionSelectBox({
  value,
  versionName,
  onChange,
  projectId,
  datasetType,
  autoOpen = false,
  onDismiss,
}: DatasetVersionSelectBoxProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const resetDialogKeyRef = useRef(0);
  const selectedInSessionRef = useRef(false);

  const [search, setSearch] = useState("");
  const [openDatasetId, setOpenDatasetId] = useState<string | null>(null);
  const [isSelectOpen, setIsSelectOpen] = useState(autoOpen);
  const [isDialogOpen, setIsDialogOpen] = useState(false);

  const {
    permissions: { canCreateDatasets },
  } = usePermissions();

  const fetchDataset = useFetchDataset();

  const parsed = parseDatasetVersionKey(value);
  const datasetId = parsed?.datasetId ?? null;
  const selectedVersionId = parsed?.versionId ?? null;

  const isDatasetMode = datasetType === DATASET_TYPE.DATASET;
  const typeLabel = isDatasetMode ? "dataset" : "test suite";
  const TypeIcon = isDatasetMode ? Database : ListChecks;

  const {
    datasets,
    isLoadingDatasets,
    versions,
    isLoadingVersions,
    filteredDatasets,
    loadMore,
    hasMore,
  } = useDatasetVersionSelect({
    projectId,
    search,
    openDatasetId,
    datasetType,
  });

  const selectedDataset = useMemo(
    () => datasets.find((d) => d.id === datasetId),
    [datasets, datasetId],
  );

  const displayValue = selectedDataset
    ? `${selectedDataset.name} / ${versionName ?? ""}`
    : null;

  const handleDatasetCreated = (newDataset: Dataset) => {
    setIsDialogOpen(false);
    fetchDataset({ datasetId: newDataset.id })
      .then((enriched) => {
        if (enriched.latest_version) {
          onChange(
            formatDatasetVersionKey(enriched.id, enriched.latest_version.id),
          );
        }
      })
      .catch(() => {
        // Dataset was created; the select list will refresh via query cache invalidation
      });
  };

  const handleSelectLatestVersion = (dataset: Dataset) => {
    if (dataset.latest_version) {
      const formattedValue = formatDatasetVersionKey(
        dataset.id,
        dataset.latest_version.id,
      );
      selectedInSessionRef.current = true;
      onChange(formattedValue);
      setIsSelectOpen(false);
      setOpenDatasetId(null);
    }
  };

  const renderNestedList = () => {
    return filteredDatasets.map((dataset) => {
      const isOpen = dataset.id === openDatasetId;
      const isSelected = dataset.id === datasetId;
      const isEmpty = !dataset.latest_version;
      const isHighlighted = isSelected || isOpen;

      return (
        <div
          key={dataset.id}
          className={cn(
            "comet-body-s group relative flex h-auto min-h-8 w-full gap-1 rounded-sm p-px pr-1.5",
            isEmpty && "opacity-50",
          )}
        >
          <div
            onClick={() => !isEmpty && handleSelectLatestVersion(dataset)}
            className={cn(
              "flex min-w-0 flex-1 items-center gap-2 rounded px-3 py-1",
              isEmpty
                ? "cursor-not-allowed"
                : "cursor-pointer group-hover:bg-primary-foreground",
              isHighlighted && !isEmpty && "bg-primary-foreground",
            )}
          >
            <TooltipWrapper content={dataset.name}>
              <div className="flex min-w-0 flex-col">
                <div className="flex min-w-0 items-center gap-1">
                  <span className="min-w-0 truncate">{dataset.name}</span>
                  {dataset.latest_version && (
                    <span className="flex shrink-0 items-center text-muted-slate">
                      <GitCommitVertical className="size-3.5" />
                      {dataset.latest_version.version_name}
                    </span>
                  )}
                </div>
                {dataset.description && (
                  <span className="comet-body-s truncate text-light-slate">
                    {dataset.description}
                  </span>
                )}
              </div>
            </TooltipWrapper>
          </div>

          {isEmpty ? (
            <div className="relative flex w-8 shrink-0 items-center justify-center self-stretch rounded">
              <TooltipWrapper content={`This ${typeLabel} is empty`}>
                <Info className="size-3.5 text-light-slate" />
              </TooltipWrapper>
            </div>
          ) : (
            <Popover open={isOpen}>
              <PopoverTrigger asChild>
                <div
                  onMouseEnter={() => setOpenDatasetId(dataset.id)}
                  onMouseLeave={() => setOpenDatasetId(null)}
                  className={cn(
                    "relative flex w-8 shrink-0 cursor-pointer items-center justify-center self-stretch rounded",
                    isHighlighted && "bg-primary-foreground",
                  )}
                >
                  <ChevronRight className="size-3.5 text-light-slate" />
                </div>
              </PopoverTrigger>

              <PopoverContent
                side="right"
                align="start"
                sideOffset={0}
                className="max-h-[200px] max-w-[180px] overflow-y-auto overflow-x-hidden p-1"
                onMouseEnter={() => setOpenDatasetId(dataset.id)}
                onMouseLeave={() => setOpenDatasetId(null)}
              >
                {isLoadingVersions ? (
                  <div className="flex items-center justify-center py-4">
                    <Spinner />
                  </div>
                ) : versions.length === 0 ? (
                  <div className="comet-body-s flex items-center justify-center p-1 text-muted-slate">
                    No versions
                  </div>
                ) : (
                  versions.map((version) => (
                    <VersionOption
                      key={version.id}
                      version={version}
                      datasetId={dataset.id}
                      isSelected={selectedVersionId === version.id}
                    />
                  ))
                )}
              </PopoverContent>
            </Popover>
          )}
        </div>
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
      return (
        <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
          No search results
        </div>
      );
    }

    return (
      <div className="min-h-0 flex-1 space-y-[3px] overflow-y-auto overflow-x-hidden">
        {renderNestedList()}
        {hasMore && (
          <>
            <div className="flex items-center justify-between gap-2 border-t border-border px-3 py-2">
              <div className="comet-body-s text-light-slate">
                Showing first {DEFAULT_LOADED_DATASETS} items.
              </div>
              <Button
                variant="link"
                onClick={loadMore}
                type="button"
                className="p-0"
              >
                Load more
              </Button>
            </div>
          </>
        )}
      </div>
    );
  };

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== "Escape") {
      event.stopPropagation();
    }
  };

  const isEmptyState =
    !isLoadingDatasets && !search && filteredDatasets.length === 0;

  // Radix Select focuses the list on open; move focus to the search instead.
  useEffect(() => {
    if (!isSelectOpen) return;
    const id = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, [isSelectOpen]);

  return (
    <>
      <div className="flex">
        <Select
          value={value || ""}
          onValueChange={(selectedValue) => {
            selectedInSessionRef.current = true;
            onChange(selectedValue);
            setIsSelectOpen(false);
            setOpenDatasetId(null);
          }}
          onOpenChange={(open) => {
            if (open) {
              selectedInSessionRef.current = false;
            } else {
              setSearch("");
              setOpenDatasetId(null);
              if (!selectedInSessionRef.current) onDismiss?.();
            }
            setIsSelectOpen(open);
          }}
          open={isSelectOpen}
        >
          <TooltipWrapper content={displayValue} hoverOnly>
            <SelectTrigger
              className={cn(
                "h-full w-auto min-w-0 max-w-[210px] gap-1 rounded-none border-0 bg-transparent px-2 py-0 text-xs shadow-none hover:shadow-none focus:border-0 [&>span]:min-w-0 [&>span]:flex-1 [&>svg]:transition-transform",
                isSelectOpen
                  ? "text-foreground"
                  : value
                    ? "group text-foreground hover:text-primary group-hover:[&>svg]:text-primary"
                    : "text-light-slate hover:text-foreground",
                { "[&>svg]:rotate-180": isSelectOpen },
              )}
            >
              <SelectValue
                placeholder={
                  <div className="flex w-full items-center">
                    <TypeIcon className="mr-2 size-3 text-[#6bdf93]" />
                    <span className="truncate font-normal">
                      Select {typeLabel}
                    </span>
                  </div>
                }
              >
                <div className="flex w-full items-center justify-between gap-1">
                  <div className="flex min-w-0 items-center gap-2">
                    <TypeIcon className="size-3 shrink-0 text-[#6bdf93]" />
                    <span className="min-w-0 truncate">
                      {selectedDataset?.name}
                    </span>
                  </div>
                  <div className="flex shrink-0 items-center text-muted-slate group-hover:text-primary">
                    <GitCommitVertical className="size-3" />
                    <span>{versionName ?? ""}</span>
                  </div>
                </div>
              </SelectValue>
            </SelectTrigger>
          </TooltipWrapper>

          <SelectContent sideOffset={6} className="w-[275px] p-0">
            <div className="flex max-h-[250px] flex-col">
              {isEmptyState ? (
                <DropdownEmptyState
                  lightImageUrl={emptyDatasetOrSuiteLightUrl}
                  darkImageUrl={emptyDatasetOrSuiteDarkUrl}
                  title={
                    isDatasetMode ? "No datasets yet" : "No test suites yet"
                  }
                  ctaLabel={
                    canCreateDatasets
                      ? isDatasetMode
                        ? "Create dataset"
                        : "Create test suite"
                      : undefined
                  }
                  onCreate={
                    canCreateDatasets
                      ? () => {
                          setIsSelectOpen(false);
                          setIsDialogOpen(true);
                        }
                      : undefined
                  }
                />
              ) : (
                <>
                  <div className="shrink-0" onKeyDown={handleSearchKeyDown}>
                    <SearchInput
                      ref={inputRef}
                      searchText={search}
                      setSearchText={setSearch}
                      variant="ghost"
                      dimension="sm"
                      disableDebounce
                    />
                  </div>
                  <Separator className="my-1 shrink-0" />
                  {renderOptions()}
                  {canCreateDatasets && (
                    <>
                      <Separator className="my-1 shrink-0" />
                      <ListAction
                        variant="default"
                        size="sm"
                        className="shrink-0"
                        onClick={() => {
                          setIsSelectOpen(false);
                          setIsDialogOpen(true);
                        }}
                      >
                        <Plus className="size-3.5 shrink-0" />
                        New {typeLabel}
                      </ListAction>
                    </>
                  )}
                </>
              )}
            </div>
          </SelectContent>
        </Select>
      </div>
      {isDatasetMode ? (
        <AddEditDatasetDialog
          key={resetDialogKeyRef.current}
          open={isDialogOpen}
          setOpen={setIsDialogOpen}
          onDatasetCreated={handleDatasetCreated}
        />
      ) : (
        <AddEditTestSuiteDialog
          key={resetDialogKeyRef.current}
          open={isDialogOpen}
          setOpen={setIsDialogOpen}
          onDatasetCreated={handleDatasetCreated}
        />
      )}
    </>
  );
}

export default DatasetVersionSelectBox;
