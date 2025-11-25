import React, { useCallback, useRef, useState } from "react";
import { Split } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";
import uniq from "lodash/uniq";
import first from "lodash/first";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/components/pages/CompareExperimentsPage/CompareExperimentsDialog";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
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
} from "@/types/shared";
import {
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_DATASET_PREFIX,
} from "@/constants/experiments";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { Separator } from "@/components/ui/separator";

const EVALUATION_EXPORT_COLUMNS = [
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
];
const FLAT_COLUMNS = [COLUMN_CREATED_AT_ID, COLUMN_ID_ID];

const extractFieldName = (column: string, prefix: string): string =>
  column.replace(`${prefix}.`, "");

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
    const scoreName = extractFieldName(column, prefixColumnKey);
    const scoreObject = item.feedback_scores?.find((f) => f.name === scoreName);
    accumulator[`${prefix}${column}`] = get(scoreObject, "value", "-");

    if (scoreObject?.reason) {
      accumulator[`${prefix}${column}_reason`] = scoreObject.reason;
    }

    return;
  }

  if (EVALUATION_EXPORT_COLUMNS.includes(prefixColumnKey)) {
    const evaluationName = extractFieldName(column, prefixColumnKey);
    accumulator[`${prefix}evaluation_task.${evaluationName}`] = get(
      item ?? {},
      keys,
      "-",
    );

    return;
  }

  // Handle dataset columns with "data." prefix
  if (prefixColumnKey === EXPERIMENT_ITEM_DATASET_PREFIX) {
    const fieldName = extractFieldName(column, prefixColumnKey);
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
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

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
            EVALUATION_EXPORT_COLUMNS.includes(prefix) ||
            prefix === COLUMN_FEEDBACK_SCORES_ID
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
      <CompareExperimentsDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
      />
      <div className="inline-flex items-center gap-2">
        <TooltipWrapper content="Compare experiments">
          <Button
            size="sm"
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Split className="mr-1.5 size-3.5" />
            Compare
          </Button>
        </TooltipWrapper>
        <ExplainerIcon
          className="-ml-0.5"
          {...EXPLAINERS_MAP[
            EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
          ]}
        />
        <Separator orientation="vertical" className="mx-2 h-4" />
      </div>
      {columnsToExport && (
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
      )}
    </div>
  );
};

export default CompareExperimentsActionsPanel;
