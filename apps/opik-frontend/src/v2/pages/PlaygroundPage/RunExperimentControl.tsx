import React, { useCallback, useMemo, useState } from "react";
import dayjs from "dayjs";
import { Database, FlaskConical, ListChecks, X } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Separator } from "@/ui/separator";
import { DatasetVersionSelectBox } from "@/v2/pages-shared/DatasetVersionSelectBox";
import MetricSelector from "@/v2/pages/PlaygroundPage/MetricSelector";

import useRulesList from "@/api/automations/useRulesList";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import { DEFAULT_LOADED_DATASETS } from "@/v2/pages-shared/DatasetVersionSelectBox/useDatasetVersionSelect";
import { useActiveProjectId } from "@/store/AppStore";
import {
  useDatasetType,
  useScoresByDatasetId,
  useSelectedRuleIds,
  useResetDatasetFilters,
  useResetOutputMap,
  useSetDatasetType,
  useSetExperimentNamePrefix,
  useSetScoresForDataset,
  useSetSelectedRuleIds,
} from "@/store/PlaygroundStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DATASET_TYPE } from "@/types/datasets";
import {
  parseDatasetVersionKey,
  toPlainDatasetId,
} from "@/utils/datasetVersionStorage";

const ENTRY_OPTIONS = [
  {
    type: DATASET_TYPE.DATASET,
    testId: "run-experiment-source-dataset",
    Icon: Database,
    iconClassName: "text-chart-burgundy",
    title: "Dataset",
    description:
      "Run prompts against a dataset. Results will be scored using selected metrics.",
  },
  {
    type: DATASET_TYPE.TEST_SUITE,
    testId: "run-experiment-source-test-suite",
    Icon: ListChecks,
    iconClassName: "text-chart-green",
    title: "Test suite",
    description:
      "Run prompts against a test suite. Results will be scored using the assertions defined on the suite.",
  },
] as const;

interface RunExperimentControlProps {
  workspaceName: string;
  datasetId: string | null;
  versionName?: string;
  onChangeDatasetId: (id: string | null) => void;
  onLeaveExperimentMode: () => void;
}

const RunExperimentControl: React.FC<RunExperimentControlProps> = ({
  workspaceName,
  datasetId,
  versionName,
  onChangeDatasetId,
  onLeaveExperimentMode,
}) => {
  const activeProjectId = useActiveProjectId();
  const storedDatasetType = useDatasetType();
  const scoresByDatasetId = useScoresByDatasetId();
  const selectedRuleIds = useSelectedRuleIds();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const setDatasetType = useSetDatasetType();
  const setExperimentNamePrefix = useSetExperimentNamePrefix();
  const setScoresForDataset = useSetScoresForDataset();
  const resetDatasetFilters = useResetDatasetFilters();
  const resetOutputMap = useResetOutputMap();

  const {
    permissions: {
      canViewDatasets,
      canCreateExperiments,
      canViewExperiments,
      canUsePlayground,
    },
  } = usePermissions();

  const [pendingType, setPendingType] = useState<DATASET_TYPE | null>(null);

  const isExperimentMode = !!datasetId;
  const activeType = isExperimentMode
    ? storedDatasetType ?? DATASET_TYPE.DATASET
    : pendingType;

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      page: 1,
      size: 100,
    },
    { enabled: !!activeProjectId && activeType === DATASET_TYPE.DATASET },
  );
  const rules = useMemo(() => rulesData?.content ?? [], [rulesData?.content]);

  const { data: datasetsData } = useProjectDatasetsList(
    {
      projectId: activeProjectId ?? "",
      page: 1,
      size: DEFAULT_LOADED_DATASETS,
    },
    { enabled: !!activeProjectId && !!activeType },
  );

  const handleDatasetChange = useCallback(
    (value: string | null) => {
      if (!value) return;

      const parsed = parseDatasetVersionKey(value);
      const newPlainId = parsed?.datasetId ?? value;
      const dataset = datasetsData?.content?.find((d) => d.id === newPlainId);
      const type = dataset?.type ?? activeType ?? DATASET_TYPE.DATASET;

      resetOutputMap();

      const isDifferentDataset = toPlainDatasetId(datasetId) !== newPlainId;
      onChangeDatasetId(value);

      const ruleIds =
        type === DATASET_TYPE.DATASET
          ? scoresByDatasetId[newPlainId] ?? []
          : [];
      setSelectedRuleIds(ruleIds);

      if (isDifferentDataset) resetDatasetFilters();

      const name = dataset?.name ?? "";
      setExperimentNamePrefix(
        `${name.replace(/\s+/g, "-")}-${dayjs().format("YYYY-MM-DD")}`,
      );
      setDatasetType(type);
      if (type === DATASET_TYPE.DATASET) {
        setScoresForDataset(newPlainId, ruleIds);
      }

      setPendingType(null);
    },
    [
      activeType,
      datasetId,
      datasetsData?.content,
      scoresByDatasetId,
      onChangeDatasetId,
      resetOutputMap,
      resetDatasetFilters,
      setSelectedRuleIds,
      setExperimentNamePrefix,
      setDatasetType,
      setScoresForDataset,
    ],
  );

  const handleMetricsChange = useCallback(
    (ruleIds: string[] | null) => {
      // Resolve "all" (null) to concrete ids so the run path keeps working with
      // an explicit metric list, matching the previous dialog behavior.
      const resolved =
        ruleIds === null && rules.length > 0 ? rules.map((r) => r.id) : ruleIds;
      setSelectedRuleIds(resolved);
      const plainId = toPlainDatasetId(datasetId);
      if (plainId) setScoresForDataset(plainId, resolved);
    },
    [rules, datasetId, setSelectedRuleIds, setScoresForDataset],
  );

  const handleClickX = useCallback(() => {
    if (isExperimentMode) {
      onLeaveExperimentMode();
    }
    setPendingType(null);
  }, [isExperimentMode, onLeaveExperimentMode]);

  if (!(canViewDatasets && canCreateExperiments && canViewExperiments)) {
    return null;
  }

  const renderSelectionBlock = (type: DATASET_TYPE) => (
    <div
      data-testid="playground-loaded-source-pill"
      data-source-type={type}
      className="flex h-6 items-center rounded-md border border-input bg-background"
    >
      <DatasetVersionSelectBox
        value={datasetId}
        versionName={versionName}
        onChange={handleDatasetChange}
        projectId={activeProjectId ?? undefined}
        datasetType={type}
        autoOpen={!isExperimentMode}
      />
      {type === DATASET_TYPE.DATASET && (
        <>
          <Separator orientation="vertical" className="h-3" />
          <MetricSelector
            rules={rules}
            selectedRuleIds={selectedRuleIds ?? []}
            onSelectionChange={handleMetricsChange}
            workspaceName={workspaceName}
            projectId={activeProjectId ?? undefined}
            canUsePlayground={canUsePlayground}
          />
        </>
      )}
      <Separator orientation="vertical" />
      <button
        className="flex h-full items-center px-1 text-light-slate hover:text-primary-hover"
        onClick={handleClickX}
        aria-label="Clear selection"
      >
        <X className="size-3.5" />
      </button>
    </div>
  );

  const renderEntryButton = () => (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          data-testid="playground-run-button"
          data-mode="experiment-trigger"
          variant="outline"
          size="2xs"
        >
          <FlaskConical className="mr-1 size-3" />
          Run experiment
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[380px] p-1">
        {ENTRY_OPTIONS.map(
          ({ type, testId, Icon, iconClassName, title, description }) => (
            <DropdownMenuItem
              key={type}
              data-testid={testId}
              className="flex-col items-start gap-1 p-3"
              onClick={() => setPendingType(type)}
            >
              <div className="flex items-center gap-2">
                <Icon className={`size-4 shrink-0 ${iconClassName}`} />
                <span className="comet-body-s-accented text-foreground">
                  {title}
                </span>
              </div>
              <span className="comet-body-s text-left text-light-slate">
                {description}
              </span>
            </DropdownMenuItem>
          ),
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );

  return activeType ? renderSelectionBlock(activeType) : renderEntryButton();
};

export default RunExperimentControl;
