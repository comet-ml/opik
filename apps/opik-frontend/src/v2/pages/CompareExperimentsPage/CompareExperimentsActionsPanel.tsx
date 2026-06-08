import React, { useCallback, useState } from "react";
import get from "lodash/get";
import slugify from "slugify";
import uniq from "lodash/uniq";
import first from "lodash/first";

import CompareExperimentsButton from "@/v2/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
import EvaluateButton from "@/v2/pages-shared/automations/EvaluateButton/EvaluateButton";
import RunEvaluationDialog from "@/v2/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import useFilteredRulesList from "@/api/automations/useFilteredRulesList";
import { getCompareExperimentsList } from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { useToast } from "@/ui/use-toast";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  Experiment,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_CREATED_AT_ID,
  COLUMN_ID_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_DURATION_ID,
  COLUMN_USAGE_ID,
} from "@/types/shared";
import {
  COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_DATASET_PREFIX,
} from "@/constants/experiments";
import { Separator } from "@/ui/separator";

const COLUMN_PASSED_ID = "passed";
const COLUMN_TOTAL_ESTIMATED_COST_ID = "total_estimated_cost";

const EXPERIMENT_ITEM_COLUMNS = [
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
  COLUMN_USAGE_ID,
  COLUMN_TOTAL_ESTIMATED_COST_ID,
];
const FLAT_COLUMNS = [COLUMN_CREATED_AT_ID, COLUMN_ID_ID];

const processNestedExportColumn = (
  item: ExperimentItem,
  column: string,
  accumulator: Record<string, unknown>,
  rowData: object,
  prefix: string = "",
) => {
  const keys = column.split(".");
  const prefixColumnKey = first(keys) as string;

  if (prefixColumnKey === COLUMN_FEEDBACK_SCORES_ID) {
    const scoreName = keys.slice(1).join(".");
    const scoreObject = item.feedback_scores?.find((f) => f.name === scoreName);
    accumulator[`${prefix}${column}`] = get(scoreObject, "value", "-");

    if (scoreObject?.reason) {
      accumulator[`${prefix}${column}_reason`] = scoreObject.reason;
    }

    return;
  }

  if (prefixColumnKey === COLUMN_PASSED_ID) {
    accumulator[`${prefix}status`] = item?.status ?? "-";

    (item?.assertion_results ?? []).forEach((ar, index) => {
      const idx = index + 1;
      accumulator[`${prefix}assertion_${idx}.name`] = ar.value;
      accumulator[`${prefix}assertion_${idx}.result`] = ar.passed
        ? "passed"
        : "failed";
      if (ar.reason) {
        accumulator[`${prefix}assertion_${idx}.reason`] = ar.reason;
      }
    });

    return;
  }

  if (EXPERIMENT_ITEM_COLUMNS.includes(prefixColumnKey)) {
    accumulator[`${prefix}${column}`] = get(item ?? {}, keys, "-");

    return;
  }

  // Handle dataset columns with "data." prefix
  if (prefixColumnKey === EXPERIMENT_ITEM_DATASET_PREFIX) {
    const fieldName = keys.slice(1).join(".");
    accumulator[`${prefix}dataset.${fieldName}`] = get(rowData, fieldName, "-");

    return;
  }
};

type CompareExperimentsActionsPanelProps = {
  getDataForExport?: () => Promise<ExperimentsCompare[]>;
  selectedRows?: ExperimentsCompare[];
  columnsToExport?: string[];
  experiments?: Experiment[];
};

const CompareExperimentsActionsPanel: React.FC<
  CompareExperimentsActionsPanelProps
> = ({ getDataForExport, selectedRows = [], columnsToExport, experiments }) => {
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const singleExperiment =
    experiments?.length === 1 ? experiments[0] : undefined;
  const evaluateProjectId = singleExperiment?.project_id ?? "";
  const evaluateEnabled = Boolean(singleExperiment && evaluateProjectId);

  const [evaluateOpen, setEvaluateOpen] = useState(false);
  const [evaluateTraceIds, setEvaluateTraceIds] = useState<string[]>([]);
  const [isFetchingTraces, setIsFetchingTraces] = useState(false);

  const { rules, isLoading: isRulesLoading } = useFilteredRulesList({
    projectId: evaluateProjectId,
    entityType: "trace",
    enabled: evaluateEnabled,
  });

  const handleEvaluateClick = useCallback(async () => {
    if (!singleExperiment?.dataset_id) return;
    setIsFetchingTraces(true);
    try {
      const data: { content?: ExperimentsCompare[] } =
        await getCompareExperimentsList(
          {},
          {
            workspaceName,
            datasetId: singleExperiment.dataset_id,
            experimentsIds: [singleExperiment.id],
            truncate: true,
            page: 1,
            size: COMPARE_EXPERIMENTS_MAX_PAGE_SIZE,
          },
        );
      const traceIds = uniq(
        (data?.content ?? [])
          .flatMap((row) => row.experiment_items ?? [])
          .map((item) => item.trace_id)
          .filter((id): id is string => Boolean(id)),
      );

      if (!traceIds.length) {
        toast({
          title: "Nothing to evaluate",
          description: "No traces are associated with this experiment.",
          variant: "destructive",
        });
        return;
      }

      setEvaluateTraceIds(traceIds);
      setEvaluateOpen(true);
    } catch (error) {
      toast({
        title: "Error",
        description: "Failed to load experiment traces for evaluation.",
        variant: "destructive",
      });
    } finally {
      setIsFetchingTraces(false);
    }
  }, [singleExperiment, workspaceName, toast]);

  const mapRowData = useCallback(async () => {
    if (!columnsToExport || !getDataForExport) return [];

    const rows = await getDataForExport();

    const localExperiments = experiments ?? [];
    const isAllNamesUnique =
      uniq(localExperiments.map((e) => e.name)).length ===
      localExperiments.length;
    const nameMap = localExperiments.reduce<Record<string, string>>(
      (accumulator, e) => {
        accumulator[e.id] = isAllNamesUnique ? e.name : `${e.name}(${e.id})`;
        return accumulator;
      },
      {},
    );

    const isCompare = localExperiments?.length > 1;

    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>(
        (accumulator, column) => {
          if (FLAT_COLUMNS.includes(column)) {
            accumulator[column] = get(row, column, "");

            return accumulator;
          }

          const prefix = first(column.split(".")) as string;
          const isDatasetColumn = !(
            EXPERIMENT_ITEM_COLUMNS.includes(prefix) ||
            prefix === COLUMN_FEEDBACK_SCORES_ID ||
            prefix === COLUMN_PASSED_ID
          );

          if (isDatasetColumn) {
            // Handle dataset columns with "data." prefix
            const fieldName =
              prefix === EXPERIMENT_ITEM_DATASET_PREFIX
                ? column.replace(`${EXPERIMENT_ITEM_DATASET_PREFIX}.`, "")
                : column;
            accumulator[`dataset.${fieldName}`] = get(row.data, fieldName, "-");

            return accumulator;
          }

          if (isCompare) {
            (row.experiment_items ?? []).forEach((item) => {
              const prefix = `${nameMap[item.experiment_id] ?? "unknown"}.`;
              processNestedExportColumn(
                item,
                column,
                accumulator,
                row.data,
                prefix,
              );
            });
          } else {
            const item = row.experiment_items?.[0];
            processNestedExportColumn(item, column, accumulator, row.data);
          }

          return accumulator;
        },
        {},
      );
    });
  }, [getDataForExport, columnsToExport, experiments]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      const fileName =
        experiments?.length === 1
          ? experiments[0].name
          : `compare ${experiments?.length}`;
      return `${slugify(fileName, {
        lower: true,
      })}.${extension}`;
    },
    [experiments],
  );

  return (
    <div className="flex items-center gap-2">
      <CompareExperimentsButton />
      {evaluateEnabled && (
        <>
          <RunEvaluationDialog
            open={evaluateOpen}
            setOpen={setEvaluateOpen}
            projectId={evaluateProjectId}
            entityIds={evaluateTraceIds}
            entityType="trace"
            rules={rules}
            isLoading={isRulesLoading}
          />
          <EvaluateButton
            isNoRules={!rules?.length}
            disabled={isFetchingTraces}
            label="Evaluate"
            onClick={handleEvaluateClick}
          />
        </>
      )}
      {columnsToExport && (
        <>
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ExportToButton
            disabled={
              disabled || columnsToExport.length === 0 || !isExportEnabled
            }
            getData={mapRowData}
            generateFileName={generateFileName}
            tooltipContent={
              !isExportEnabled
                ? "Export functionality is disabled for this installation"
                : undefined
            }
          />
        </>
      )}
    </div>
  );
};

export default CompareExperimentsActionsPanel;
