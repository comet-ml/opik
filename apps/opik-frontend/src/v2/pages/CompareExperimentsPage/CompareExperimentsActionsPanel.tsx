import React, { useCallback } from "react";
import { Download, Loader2 } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";
import uniq from "lodash/uniq";
import first from "lodash/first";

import EvaluateExperimentTracesButton from "@/v2/pages/CompareExperimentsPage/EvaluateExperimentTracesButton/EvaluateExperimentTracesButton";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import useStartExperimentItemsExportMutation from "@/api/datasets/useStartExperimentItemsExportMutation";
import {
  useAddExportJob,
  useSetPanelExpanded,
} from "@/store/DatasetExportStore";
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
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_DATASET_PREFIX,
} from "@/constants/experiments";

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
  columnsToExport?: string[];
  experiments?: Experiment[];
  datasetId?: string;
  experimentsIds?: string[];
  hasSelection?: boolean;
};

const CompareExperimentsActionsPanel: React.FC<
  CompareExperimentsActionsPanelProps
> = ({
  getDataForExport,
  columnsToExport,
  experiments,
  datasetId,
  experimentsIds = [],
  hasSelection = false,
}) => {
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);
  const isExportJobEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );
  const { toast } = useToast();
  const addExportJob = useAddExportJob();
  const setPanelExpanded = useSetPanelExpanded();
  const { mutate: startExport, isPending: isExportStarting } =
    useStartExperimentItemsExportMutation();

  // A hand-picked selection is bounded by the page, so it exports in the browser straight away. The whole result
  // set can be arbitrarily large, so it goes through the server-side job when that pipeline is available.
  const useExportJob =
    !hasSelection && isExportJobEnabled && Boolean(datasetId);

  const startExportJobHandler = useCallback(() => {
    if (!datasetId) return;

    startExport(
      { datasetId, experimentsIds },
      {
        onSuccess: (job) => {
          addExportJob(job, job.resource_name ?? "experiment results");
          setPanelExpanded(true);
        },
        onError: () => {
          toast({
            title: "Export failed",
            description: "Failed to start the export. Please try again.",
            variant: "destructive",
          });
        },
      },
    );
  }, [
    datasetId,
    experimentsIds,
    startExport,
    addExportJob,
    setPanelExpanded,
    toast,
  ]);

  const singleExperiment =
    experiments?.length === 1 ? experiments[0] : undefined;

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
      <EvaluateExperimentTracesButton experiment={singleExperiment} />
      {columnsToExport &&
        (useExportJob ? (
          <TooltipWrapper
            content={
              isExportEnabled
                ? "Export all results"
                : "Export functionality is disabled for this installation"
            }
          >
            <Button
              variant="outline"
              size="icon-2xs"
              onClick={startExportJobHandler}
              disabled={!isExportEnabled || isExportStarting}
            >
              {isExportStarting ? (
                <Loader2 className="animate-spin" />
              ) : (
                <Download />
              )}
            </Button>
          </TooltipWrapper>
        ) : (
          <ExportToButton
            buttonSize="icon-2xs"
            disabled={columnsToExport.length === 0 || !isExportEnabled}
            getData={mapRowData}
            generateFileName={generateFileName}
            tooltipContent={
              !isExportEnabled
                ? "Export functionality is disabled for this installation"
                : undefined
            }
          />
        ))}
    </div>
  );
};

export default CompareExperimentsActionsPanel;
