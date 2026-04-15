import React, { useCallback, useEffect, useMemo, useState } from "react";
import dayjs from "dayjs";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Label } from "@/ui/label";
import { DatasetVersionSelectBox } from "@/v2/pages-shared/DatasetVersionSelectBox";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import MetricSelector from "@/v2/pages/PlaygroundPage/MetricSelector";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditRuleDialog from "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";

import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import useRulesList from "@/api/automations/useRulesList";

import { useIsRunning } from "@/store/PlaygroundStore";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Dataset, DatasetItemColumn, DATASET_TYPE } from "@/types/datasets";
import { Filters } from "@/types/filters";
import {
  buildDatasetFilterColumns,
  transformDataColumnFilters,
} from "@/lib/filters";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";

import { DEFAULT_LOADED_DATASETS } from "@/v2/pages-shared/DatasetVersionSelectBox/useDatasetVersionSelect";

const MAX_VERSIONS_TO_FETCH = 1000;
const EMPTY_DATASETS: Dataset[] = [];

interface RunOnDatasetDialogProps {
  open: boolean;
  onClose: () => void;
  onRun: (params: {
    datasetId: string;
    versionId?: string;
    datasetName: string;
    datasetType: DATASET_TYPE;
    selectedRuleIds: string[] | null;
    experimentNamePrefix: string;
    filters: Filters;
  }) => void;
  workspaceName: string;
  initialDatasetId?: string | null;
  initialSelectedRuleIds?: string[] | null;
  initialFilters?: Filters;
}

const getRunDisabledTooltip = ({
  isRunning,
  isDatasetEmpty,
  hasFilters,
  isTestSuite,
}: {
  isRunning: boolean;
  isDatasetEmpty: boolean;
  hasFilters: boolean;
  isTestSuite: boolean;
}): string | undefined => {
  if (isRunning) return "An experiment is already running";
  if (isDatasetEmpty && hasFilters) return "No items match the current filters";
  if (isDatasetEmpty) {
    return `Selected ${isTestSuite ? "test suite" : "dataset"} is empty`;
  }
  return undefined;
};

const RunOnDatasetDialog: React.FC<RunOnDatasetDialogProps> = ({
  open,
  onClose,
  onRun,
  workspaceName,
  initialDatasetId = null,
  initialSelectedRuleIds = null,
  initialFilters = [],
}) => {
  const [datasetId, setDatasetId] = useState<string | null>(initialDatasetId);
  const [selectedRuleIds, setSelectedRuleIds] = useState<string[] | null>(
    initialSelectedRuleIds,
  );
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [experimentPrefix, setExperimentPrefix] = useState("");
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);

  const isRunning = useIsRunning();
  const activeProjectId = useActiveProjectId();

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  useEffect(() => {
    if (open) {
      setDatasetId(initialDatasetId);
      setSelectedRuleIds(initialSelectedRuleIds);
      setFilters(initialFilters);
      setExperimentPrefix("");
    }
  }, [open, initialDatasetId, initialSelectedRuleIds, initialFilters]);

  const parsedDatasetId = parseDatasetVersionKey(datasetId);
  const plainDatasetId = parsedDatasetId?.datasetId || datasetId;

  const { data: datasetsData } = useProjectDatasetsList(
    {
      projectId: activeProjectId ?? "",
      page: 1,
      size: DEFAULT_LOADED_DATASETS,
    },
    { enabled: open && !!activeProjectId },
  );
  const datasets = datasetsData?.content || EMPTY_DATASETS;
  const selectedDataset = datasets.find((ds) => ds.id === plainDatasetId);
  const datasetName = selectedDataset?.name || null;
  const selectedDatasetType = selectedDataset?.type ?? null;
  const isTestSuite = selectedDatasetType === DATASET_TYPE.TEST_SUITE;

  const { data: versionsData } = useDatasetVersionsList(
    { datasetId: plainDatasetId!, page: 1, size: MAX_VERSIONS_TO_FETCH },
    { enabled: !!plainDatasetId && open },
  );
  const { version_name: versionName, version_hash: versionHash } = useMemo(
    () =>
      versionsData?.content?.find((v) => v.id === parsedDatasetId?.versionId) ??
      ({} as { version_name?: string; version_hash?: string }),
    [parsedDatasetId?.versionId, versionsData?.content],
  );

  useEffect(() => {
    if (!experimentPrefix && datasetName) {
      const date = dayjs().format("YYYY-MM-DD");
      setExperimentPrefix(`${datasetName.replace(/\s+/g, "-")}-${date}`);
    }
  }, [datasetName, experimentPrefix]);

  const transformedFilters = useMemo(
    () => (filters.length ? transformDataColumnFilters(filters) : undefined),
    [filters],
  );

  const { data: datasetItemsData, isLoading: isLoadingDatasetItems } =
    useDatasetItemsList(
      {
        datasetId: plainDatasetId!,
        page: 1,
        size: 1,
        truncate: true,
        filters: transformedFilters,
        versionId: versionHash,
      },
      { enabled: !!plainDatasetId && open },
    );

  const datasetColumns: DatasetItemColumn[] = useMemo(
    () => datasetItemsData?.columns || [],
    [datasetItemsData?.columns],
  );

  const isDatasetEmpty =
    !isLoadingDatasetItems && !!plainDatasetId && datasetItemsData?.total === 0;

  const filtersColumnData = useMemo(
    () => buildDatasetFilterColumns(datasetColumns),
    [datasetColumns],
  );

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      page: 1,
      size: 100,
    },
    { enabled: !!activeProjectId && open },
  );
  const rules = rulesData?.content || [];

  const handleDatasetChange = useCallback((value: string | null) => {
    setDatasetId(value);
    setFilters([]);
    setExperimentPrefix("");
  }, []);

  const handleRun = useCallback(() => {
    if (!datasetId || !datasetName) return;
    onRun({
      datasetId,
      versionId: parsedDatasetId?.versionId,
      datasetName,
      datasetType: selectedDatasetType ?? DATASET_TYPE.DATASET,
      selectedRuleIds: isTestSuite ? [] : selectedRuleIds,
      experimentNamePrefix: experimentPrefix,
      filters,
    });
    onClose();
  }, [
    datasetId,
    datasetName,
    parsedDatasetId?.versionId,
    selectedDatasetType,
    isTestSuite,
    selectedRuleIds,
    experimentPrefix,
    filters,
    onRun,
    onClose,
  ]);

  const isRunDisabled =
    !datasetId ||
    !datasetName ||
    isDatasetEmpty ||
    isLoadingDatasetItems ||
    isRunning;

  return (
    <>
      <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
        <DialogContent
          className="max-w-lg sm:max-w-[560px]"
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <DialogHeader className="pb-0">
            <DialogTitle>Test on dataset</DialogTitle>
            <DialogDescription>
              Run your prompt suite against a dataset or test suite and score
              results with selected metrics.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-4 overflow-y-auto pb-2">
            <div className="flex flex-col gap-1.5">
              <Label>Dataset / Test suite</Label>
              <div className="flex items-center gap-2">
                <div className="min-w-0 flex-1">
                  <DatasetVersionSelectBox
                    value={datasetId}
                    versionName={versionName}
                    onChange={handleDatasetChange}
                    projectId={activeProjectId ?? undefined}
                    buttonClassName="w-full"
                  />
                </div>
                {datasetId && (
                  <FiltersButton
                    columns={filtersColumnData}
                    filters={filters}
                    onChange={setFilters}
                    layout="icon"
                    deferOnChange
                  />
                )}
              </div>
            </div>

            {plainDatasetId && selectedDataset && !isTestSuite && (
              <div className="flex flex-col gap-1.5">
                <Label>Metrics</Label>
                <MetricSelector
                  rules={rules}
                  selectedRuleIds={selectedRuleIds}
                  onSelectionChange={setSelectedRuleIds}
                  datasetId={datasetId}
                  onCreateRuleClick={() => setIsRuleDialogOpen(true)}
                  workspaceName={workspaceName}
                  projectId={activeProjectId ?? undefined}
                  canUsePlayground={!!activeProjectId || canCreateProjects}
                />
              </div>
            )}
          </div>

          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <TooltipWrapper
              content={getRunDisabledTooltip({
                isRunning,
                isDatasetEmpty,
                hasFilters: filters.length > 0,
                isTestSuite,
              })}
            >
              <Button
                onClick={handleRun}
                disabled={isRunDisabled}
                style={isRunDisabled ? { pointerEvents: "auto" } : {}}
              >
                {isTestSuite ? "Use test suite" : "Use dataset"}
              </Button>
            </TooltipWrapper>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AddEditRuleDialog
        open={isRuleDialogOpen}
        setOpen={setIsRuleDialogOpen}
        projectId={activeProjectId || ""}
        datasetColumnNames={datasetColumns.map((c) => c.name)}
        hideScopeSelector
      />
    </>
  );
};

export default RunOnDatasetDialog;
