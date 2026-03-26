import React, { useCallback, useEffect, useMemo, useState } from "react";
import dayjs from "dayjs";
import { useQueryClient } from "@tanstack/react-query";

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

import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import useProjectByName from "@/api/projects/useProjectByName";
import useRulesList from "@/api/automations/useRulesList";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";

import { useIsRunning } from "@/store/PlaygroundStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Dataset, DatasetItemColumn } from "@/types/datasets";
import { Filters } from "@/types/filters";
import {
  buildDatasetFilterColumns,
  transformDataColumnFilters,
} from "@/lib/filters";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
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
    selectedRuleIds: string[] | null;
    experimentNamePrefix: string;
    filters: Filters;
  }) => void;
  workspaceName: string;
  initialDatasetId?: string | null;
  initialSelectedRuleIds?: string[] | null;
  initialFilters?: Filters;
}

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
  const [ruleDialogProjectId, setRuleDialogProjectId] = useState<
    string | undefined
  >(undefined);

  const isRunning = useIsRunning();
  const queryClient = useQueryClient();
  const createProjectMutation = useProjectCreateMutation();

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

  const { data: datasetsData } = useDatasetsList(
    { workspaceName, page: 1, size: DEFAULT_LOADED_DATASETS },
    { enabled: open },
  );
  const datasets = datasetsData?.content || EMPTY_DATASETS;

  const parsedDatasetId = parseDatasetVersionKey(datasetId);
  const plainDatasetId = parsedDatasetId?.datasetId || datasetId;
  const datasetName =
    datasets.find((ds) => ds.id === plainDatasetId)?.name || null;

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

  const {
    data: playgroundProject,
    isError: isProjectError,
    error: projectError,
  } = useProjectByName(
    { projectName: PLAYGROUND_PROJECT_NAME },
    { enabled: !!workspaceName && open, retry: false },
  );

  const isProjectNotFound =
    isProjectError &&
    projectError &&
    "response" in projectError &&
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (projectError as any).response?.status === 404;

  const canUsePlayground = !!playgroundProject?.id || canCreateProjects;

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: playgroundProject?.id,
      page: 1,
      size: 100,
    },
    { enabled: !!playgroundProject?.id && open },
  );
  const rules = rulesData?.content || [];

  const handleCreateRuleClick = useCallback(async () => {
    try {
      let projectId: string | undefined = playgroundProject?.id;
      if (!projectId && isProjectNotFound && canCreateProjects) {
        const result = await createProjectMutation.mutateAsync({
          project: { name: PLAYGROUND_PROJECT_NAME },
        });
        projectId = result.id;
        await queryClient.refetchQueries({
          queryKey: ["project", { projectName: PLAYGROUND_PROJECT_NAME }],
        });
      }
      if (projectId || playgroundProject?.id) {
        setRuleDialogProjectId(projectId || playgroundProject?.id);
        setIsRuleDialogOpen(true);
      }
    } catch (error) {
      console.error("Failed to create playground project:", error);
    }
  }, [
    playgroundProject,
    isProjectNotFound,
    createProjectMutation,
    queryClient,
    canCreateProjects,
  ]);

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
      selectedRuleIds,
      experimentNamePrefix: experimentPrefix,
      filters,
    });
    onClose();
  }, [
    datasetId,
    datasetName,
    parsedDatasetId?.versionId,
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
            <DialogTitle>Run on dataset</DialogTitle>
            <DialogDescription>
              Run your prompt suite against a dataset and score results with
              selected metrics.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-4 overflow-y-auto pb-2">
            <div className="flex flex-col gap-1.5">
              <Label>Dataset</Label>
              <div className="flex items-center gap-2">
                <div className="min-w-0 flex-1">
                  <DatasetVersionSelectBox
                    value={datasetId}
                    versionName={versionName}
                    onChange={handleDatasetChange}
                    workspaceName={workspaceName}
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

            <div className="flex flex-col gap-1.5">
              <Label>Metrics</Label>
              <MetricSelector
                rules={rules}
                selectedRuleIds={selectedRuleIds}
                onSelectionChange={setSelectedRuleIds}
                datasetId={datasetId}
                onCreateRuleClick={handleCreateRuleClick}
                workspaceName={workspaceName}
                projectId={playgroundProject?.id}
                canUsePlayground={canUsePlayground}
              />
            </div>
          </div>

          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <TooltipWrapper
              content={
                isRunning
                  ? "An experiment is already running"
                  : isDatasetEmpty && filters.length > 0
                    ? "No items match the current filters"
                    : isDatasetEmpty
                      ? "Selected dataset is empty"
                      : undefined
              }
            >
              <Button
                onClick={handleRun}
                disabled={isRunDisabled}
                style={isRunDisabled ? { pointerEvents: "auto" } : {}}
              >
                Use dataset
              </Button>
            </TooltipWrapper>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AddEditRuleDialog
        open={isRuleDialogOpen}
        setOpen={(o) => {
          setIsRuleDialogOpen(o);
          if (!o) setRuleDialogProjectId(undefined);
        }}
        projectId={ruleDialogProjectId || playgroundProject?.id || ""}
        projectName={PLAYGROUND_PROJECT_NAME}
        datasetColumnNames={datasetColumns.map((c) => c.name)}
        hideScopeSelector
      />
    </>
  );
};

export default RunOnDatasetDialog;
