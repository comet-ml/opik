import React, { useCallback, useEffect, useMemo, useState } from "react";
import dayjs from "dayjs";
import { Database, ListChecks } from "lucide-react";

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
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { DatasetVersionSelectBox } from "@/v2/pages-shared/DatasetVersionSelectBox";
import MetricSelector from "@/v2/pages/PlaygroundPage/MetricSelector";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AddEditRuleDialog from "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";

import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import useRulesList from "@/api/automations/useRulesList";

import {
  useIsRunning,
  useRecentDatasetIdByType,
  useScoresByDatasetId,
} from "@/store/PlaygroundStore";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import {
  parseDatasetVersionKey,
  toPlainDatasetId,
} from "@/utils/datasetVersionStorage";

import { DEFAULT_LOADED_DATASETS } from "@/v2/pages-shared/DatasetVersionSelectBox/useDatasetVersionSelect";

import EmptyDatasetState from "./EmptyDatasetState";
import { selectDefaultType } from "./selectDefaultType";

const MAX_VERSIONS_TO_FETCH = 1000;
const EMPTY_DATASETS: Dataset[] = [];

interface RunExperimentDialogProps {
  open: boolean;
  onClose: () => void;
  onRun: (params: {
    datasetId: string;
    versionId?: string;
    datasetName: string;
    datasetType: DATASET_TYPE;
    selectedRuleIds: string[] | null;
    experimentNamePrefix: string;
  }) => void;
  workspaceName: string;
  initialDatasetId?: string | null;
  initialDatasetType?: DATASET_TYPE | null;
  initialSelectedRuleIds?: string[] | null;
}

const SEGMENT_COPY = {
  [DATASET_TYPE.DATASET]: {
    description:
      "Run prompts against a dataset. Results will be scored using selected metrics.",
    selectLabel: "Dataset",
    submitLabel: "Use dataset",
    emptyTooltip: "Selected dataset is empty",
    showMetrics: true,
  },
  [DATASET_TYPE.TEST_SUITE]: {
    description:
      "Run prompts against a test suite. Results will be scored using the assertions defined on the suite.",
    selectLabel: "Test suite",
    submitLabel: "Use test suite",
    emptyTooltip: "Selected test suite is empty",
    showMetrics: false,
  },
} as const;

const RunExperimentDialog: React.FC<RunExperimentDialogProps> = ({
  open,
  onClose,
  onRun,
  workspaceName,
  initialDatasetId = null,
  initialDatasetType = null,
  initialSelectedRuleIds = null,
}) => {
  const isRunning = useIsRunning();
  const activeProjectId = useActiveProjectId();
  const recentDatasetIdByType = useRecentDatasetIdByType();
  const scoresByDatasetId = useScoresByDatasetId();

  const {
    permissions: { canCreateDatasets, canUsePlayground },
  } = usePermissions();

  const [selectedType, setSelectedType] = useState<DATASET_TYPE>(
    initialDatasetType ?? DATASET_TYPE.DATASET,
  );
  const [datasetIdByType, setDatasetIdByType] = useState<
    Partial<Record<DATASET_TYPE, string | null>>
  >({});
  const [scoresByType, setScoresByType] = useState<
    Partial<Record<DATASET_TYPE, string[] | null>>
  >({});
  const [experimentPrefix, setExperimentPrefix] = useState("");
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);
  const [didApplyDefaults, setDidApplyDefaults] = useState(false);

  const config = SEGMENT_COPY[selectedType];

  const { data: datasetsData } = useProjectDatasetsList(
    {
      projectId: activeProjectId ?? "",
      page: 1,
      size: DEFAULT_LOADED_DATASETS,
    },
    { enabled: open && !!activeProjectId },
  );
  const datasets = datasetsData?.content ?? EMPTY_DATASETS;

  const { datasetsOnly, testSuitesOnly } = useMemo(() => {
    const datasetsOnly: Dataset[] = [];
    const testSuitesOnly: Dataset[] = [];
    for (const ds of datasets) {
      if (ds.type === DATASET_TYPE.TEST_SUITE) testSuitesOnly.push(ds);
      else datasetsOnly.push(ds);
    }
    return { datasetsOnly, testSuitesOnly };
  }, [datasets]);

  const hasDatasets = datasetsOnly.length > 0;
  const hasTestSuites = testSuitesOnly.length > 0;

  // Reset internal state when dialog opens
  useEffect(() => {
    if (!open) {
      setDidApplyDefaults(false);
      return;
    }
    setExperimentPrefix("");
    setDatasetIdByType(
      initialDatasetId
        ? { [initialDatasetType ?? DATASET_TYPE.DATASET]: initialDatasetId }
        : {},
    );
    setScoresByType(
      (initialDatasetType === DATASET_TYPE.DATASET ||
        initialDatasetType === DATASET_TYPE.TEST_SUITE) &&
        initialDatasetId
        ? { [initialDatasetType]: initialSelectedRuleIds }
        : {},
    );
    setSelectedType(initialDatasetType ?? DATASET_TYPE.DATASET);
  }, [open, initialDatasetId, initialDatasetType, initialSelectedRuleIds]);

  // Resolves the dataset id + scores for a segment, validated against the current list.
  const resolveSlotForType = useCallback(
    (type: DATASET_TYPE) => {
      const list =
        type === DATASET_TYPE.TEST_SUITE ? testSuitesOnly : datasetsOnly;
      const recent = recentDatasetIdByType[type] ?? null;
      if (!recent) return { datasetId: null, scores: null };
      const plainRecent = toPlainDatasetId(recent);
      if (!list.some((d) => d.id === plainRecent)) {
        return { datasetId: null, scores: null };
      }
      const stored = scoresByDatasetId[plainRecent];
      return { datasetId: recent, scores: stored ?? null };
    },
    [datasetsOnly, testSuitesOnly, recentDatasetIdByType, scoresByDatasetId],
  );

  // Writes the resolved slot only if the segment hasn't already been hydrated.
  const hydrateSlot = useCallback(
    (type: DATASET_TYPE) => {
      const { datasetId, scores } = resolveSlotForType(type);
      setDatasetIdByType((prev) =>
        prev[type] !== undefined ? prev : { ...prev, [type]: datasetId },
      );
      setScoresByType((prev) =>
        prev[type] !== undefined ? prev : { ...prev, [type]: scores },
      );
    },
    [resolveSlotForType],
  );

  // One-shot init: apply smart default and hydrate its slot once datasets are loaded.
  useEffect(() => {
    if (!open || didApplyDefaults || initialDatasetType || !datasetsData)
      return;
    const next = selectDefaultType({
      hasDatasets,
      hasTestSuites,
      currentDatasetType: null,
    });
    setSelectedType(next);
    hydrateSlot(next);
    setDidApplyDefaults(true);
  }, [
    open,
    didApplyDefaults,
    initialDatasetType,
    datasetsData,
    hasDatasets,
    hasTestSuites,
    hydrateSlot,
  ]);

  // Drop a stored dataset id if it no longer exists in the list (e.g. it was deleted
  // server-side while the dialog was open) so we never run with a stale selection.
  useEffect(() => {
    if (!datasetsData) return;
    setDatasetIdByType((prev) => {
      let next = prev;
      ([DATASET_TYPE.DATASET, DATASET_TYPE.TEST_SUITE] as const).forEach(
        (type) => {
          const current = prev[type];
          if (!current) return;
          const list =
            type === DATASET_TYPE.TEST_SUITE ? testSuitesOnly : datasetsOnly;
          const plain = toPlainDatasetId(current);
          if (!list.some((d) => d.id === plain)) {
            if (next === prev) next = { ...prev };
            next[type] = null;
          }
        },
      );
      return next;
    });
  }, [datasetsData, datasetsOnly, testSuitesOnly]);

  const datasetId = datasetIdByType[selectedType] ?? null;

  const parsedDatasetId = parseDatasetVersionKey(datasetId);
  const plainDatasetId = parsedDatasetId?.datasetId || datasetId;

  const selectedDataset = datasets.find((d) => d.id === plainDatasetId);
  const datasetName = selectedDataset?.name ?? null;
  const selectedDatasetType = selectedDataset?.type ?? null;

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

  // auto-fill experiment name prefix when dataset selected
  useEffect(() => {
    if (!experimentPrefix && datasetName) {
      const date = dayjs().format("YYYY-MM-DD");
      setExperimentPrefix(`${datasetName.replace(/\s+/g, "-")}-${date}`);
    }
  }, [datasetName, experimentPrefix]);

  const { data: datasetItemsData, isLoading: isLoadingDatasetItems } =
    useDatasetItemsList(
      {
        datasetId: plainDatasetId!,
        page: 1,
        size: 1,
        truncate: true,
        versionId: versionHash,
      },
      { enabled: !!plainDatasetId && open },
    );

  const isDatasetEmpty =
    !isLoadingDatasetItems && !!plainDatasetId && datasetItemsData?.total === 0;

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      page: 1,
      size: 100,
    },
    { enabled: !!activeProjectId && open },
  );
  const rules = useMemo(() => rulesData?.content ?? [], [rulesData?.content]);

  const handleDatasetChange = useCallback(
    (value: string | null) => {
      setDatasetIdByType((prev) => ({ ...prev, [selectedType]: value }));
      setExperimentPrefix("");

      // hydrate scores for the newly selected dataset
      if (value) {
        const plain = toPlainDatasetId(value);
        const stored = scoresByDatasetId[plain];
        setScoresByType((prev) => ({
          ...prev,
          [selectedType]: stored !== undefined ? stored : null,
        }));
      } else {
        setScoresByType((prev) => ({ ...prev, [selectedType]: null }));
      }
    },
    [selectedType, scoresByDatasetId],
  );

  const handleSelectedRuleIdsChange = useCallback(
    (ruleIds: string[] | null) => {
      setScoresByType((prev) => ({ ...prev, [selectedType]: ruleIds }));
    },
    [selectedType],
  );

  const handleTypeChange = useCallback(
    (value: string) => {
      if (!value) return;
      const next = value as DATASET_TYPE;
      setSelectedType(next);
      setExperimentPrefix("");
      // Mark defaults applied so an in-flight smart-default effect won't override the user's manual toggle
      setDidApplyDefaults(true);
      hydrateSlot(next);
    },
    [hydrateSlot],
  );

  const handleEmptyDatasetCreated = useCallback(
    (created: { id: string }) => {
      setDatasetIdByType((prev) => ({ ...prev, [selectedType]: created.id }));
    },
    [selectedType],
  );

  const selectedRuleIds = scoresByType[selectedType] ?? null;

  const handleRun = useCallback(() => {
    if (!datasetId || !datasetName) return;

    const resolvedRuleIds = config.showMetrics
      ? selectedRuleIds === null && rules.length > 0
        ? rules.map((r) => r.id)
        : selectedRuleIds
      : [];

    onRun({
      datasetId,
      versionId: parsedDatasetId?.versionId,
      datasetName,
      datasetType: selectedDatasetType ?? selectedType,
      selectedRuleIds: resolvedRuleIds,
      experimentNamePrefix: experimentPrefix,
    });
    onClose();
  }, [
    datasetId,
    datasetName,
    parsedDatasetId?.versionId,
    selectedDatasetType,
    selectedType,
    config,
    selectedRuleIds,
    rules,
    experimentPrefix,
    onRun,
    onClose,
  ]);

  const isRunDisabled =
    !datasetId ||
    !datasetName ||
    isDatasetEmpty ||
    isLoadingDatasetItems ||
    isRunning;

  const showEmptyState =
    selectedType === DATASET_TYPE.DATASET ? !hasDatasets : !hasTestSuites;

  const datasetColumnNames = useMemo(
    () => datasetItemsData?.columns?.map((c) => c.name) ?? [],
    [datasetItemsData?.columns],
  );

  return (
    <>
      <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
        <DialogContent
          className="max-w-lg sm:max-w-[560px]"
          onOpenAutoFocus={(e) => e.preventDefault()}
        >
          <DialogHeader className="pb-0">
            <DialogTitle>Run experiment</DialogTitle>
          </DialogHeader>

          <div className="flex flex-col gap-3 overflow-y-auto pb-2">
            <ToggleGroup
              type="single"
              variant="ghost"
              value={selectedType}
              onValueChange={handleTypeChange}
              className="w-fit justify-start"
            >
              <ToggleGroupItem
                value={DATASET_TYPE.DATASET}
                aria-label="Dataset"
                className="gap-1.5"
              >
                <Database className="size-3.5" />
                <span>Dataset</span>
              </ToggleGroupItem>
              <ToggleGroupItem
                value={DATASET_TYPE.TEST_SUITE}
                aria-label="Test suite"
                className="gap-1.5"
              >
                <ListChecks className="size-3.5" />
                <span>Test suite</span>
              </ToggleGroupItem>
            </ToggleGroup>

            <DialogDescription className="mt-0">
              {config.description}
            </DialogDescription>

            {showEmptyState ? (
              <EmptyDatasetState
                type={selectedType}
                onCreated={handleEmptyDatasetCreated}
                canCreate={canCreateDatasets}
              />
            ) : (
              <>
                <div className="flex flex-col gap-1.5">
                  <Label>{config.selectLabel}</Label>
                  <DatasetVersionSelectBox
                    value={datasetId}
                    versionName={versionName}
                    onChange={handleDatasetChange}
                    projectId={activeProjectId ?? undefined}
                    buttonClassName="w-full"
                    datasetType={selectedType}
                  />
                </div>

                {config.showMetrics && (
                  <div className="flex flex-col gap-1.5">
                    <Label>Metrics</Label>
                    <MetricSelector
                      rules={rules}
                      selectedRuleIds={selectedRuleIds}
                      onSelectionChange={handleSelectedRuleIdsChange}
                      onCreateRuleClick={() => setIsRuleDialogOpen(true)}
                      workspaceName={workspaceName}
                      projectId={activeProjectId ?? undefined}
                      canUsePlayground={canUsePlayground}
                    />
                  </div>
                )}
              </>
            )}
          </div>

          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <TooltipWrapper
              content={
                isRunning
                  ? "An experiment is already running"
                  : isDatasetEmpty
                    ? config.emptyTooltip
                    : undefined
              }
            >
              <Button
                onClick={handleRun}
                disabled={isRunDisabled || showEmptyState}
                style={
                  isRunDisabled || showEmptyState
                    ? { pointerEvents: "auto" }
                    : {}
                }
              >
                {config.submitLabel}
              </Button>
            </TooltipWrapper>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AddEditRuleDialog
        open={isRuleDialogOpen}
        setOpen={setIsRuleDialogOpen}
        projectId={activeProjectId || ""}
        datasetColumnNames={datasetColumnNames}
        hideScopeSelector
      />
    </>
  );
};

export default RunExperimentDialog;
