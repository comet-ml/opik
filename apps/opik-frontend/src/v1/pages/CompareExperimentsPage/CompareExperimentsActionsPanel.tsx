import React, { useCallback } from "react";
import get from "lodash/get";
import slugify from "slugify";
import uniq from "lodash/uniq";
import first from "lodash/first";
import groupBy from "lodash/groupBy";

import CompareExperimentsButton from "@/v1/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
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
  COLUMN_PASSED_ID,
} from "@/types/shared";
import {
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_DATASET_PREFIX,
} from "@/constants/experiments";
import { Separator } from "@/ui/separator";
import {
  getExperimentExportIds,
  getExperimentExportPrefix,
} from "@/lib/compare-experiments-export";
import { processPassedExportColumn } from "./compareExperimentsExportUtils";

const EVALUATION_EXPORT_COLUMNS = [
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
];
const FLAT_COLUMNS = [COLUMN_CREATED_AT_ID, COLUMN_ID_ID];

const extractFieldName = (column: string, prefix: string): string =>
  column.replace(`${prefix}.`, "");

const processNestedExportColumn = (
  item: ExperimentItem | undefined,
  column: string,
  accumulator: Record<string, unknown>,
  rowData: object,
  prefix: string = "",
) => {
  const keys = column.split(".");
  const prefixColumnKey = first(keys) as string;

  if (prefixColumnKey === COLUMN_FEEDBACK_SCORES_ID) {
    const scoreName = extractFieldName(column, prefixColumnKey);
    const scoreObject = item?.feedback_scores?.find(
      (f) => f.name === scoreName,
    );
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
  experimentsIds?: string[];
};

const CompareExperimentsActionsPanel: React.FC<
  CompareExperimentsActionsPanelProps
> = ({
  getDataForExport,
  selectedRows = [],
  columnsToExport,
  experiments,
  experimentsIds = [],
}) => {
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

    const isCompare = experimentsIds.length > 1 || localExperiments.length > 1;

    return rows.map((row) => {
      const itemsByExperiment = groupBy(
        row.experiment_items ?? [],
        (item) => item.experiment_id,
      );
      const rowExperimentIds = Object.keys(itemsByExperiment);
      const exportExperimentIds = getExperimentExportIds(
        experimentsIds,
        localExperiments,
        rowExperimentIds,
      );
      const shouldExportByExperiment = exportExperimentIds.length > 1;

      return columnsToExport.reduce<Record<string, unknown>>(
        (accumulator, column) => {
          if (FLAT_COLUMNS.includes(column)) {
            accumulator[column] = get(row, column, "");

            return accumulator;
          }

          const columnPrefix = first(column.split(".")) as string;
          const isDatasetColumn = !(
            EVALUATION_EXPORT_COLUMNS.includes(columnPrefix) ||
            columnPrefix === COLUMN_FEEDBACK_SCORES_ID ||
            columnPrefix === COLUMN_PASSED_ID
          );

          if (isDatasetColumn) {
            // Handle dataset columns with "data." prefix
            const fieldName =
              columnPrefix === EXPERIMENT_ITEM_DATASET_PREFIX
                ? column.replace(`${EXPERIMENT_ITEM_DATASET_PREFIX}.`, "")
                : column;
            accumulator[`dataset.${fieldName}`] = get(row.data, fieldName, "-");

            return accumulator;
          }

          if (column === COLUMN_PASSED_ID) {
            if (shouldExportByExperiment) {
              exportExperimentIds.forEach((experimentId) => {
                const items = itemsByExperiment[experimentId] ?? [];
                const experimentPrefix = getExperimentExportPrefix(
                  experimentId,
                  nameMap,
                );
                processPassedExportColumn(
                  row,
                  items,
                  accumulator,
                  experimentPrefix,
                  experimentId,
                );
              });
            } else {
              processPassedExportColumn(
                row,
                row.experiment_items ?? [],
                accumulator,
              );
            }

            return accumulator;
          }

          if (isCompare) {
            exportExperimentIds.forEach((experimentId) => {
              const experimentPrefix = getExperimentExportPrefix(
                experimentId,
                nameMap,
              );
              const items = itemsByExperiment[experimentId] ?? [];

              if (items.length === 0) {
                processNestedExportColumn(
                  undefined,
                  column,
                  accumulator,
                  row.data,
                  experimentPrefix,
                );
              }

              items.forEach((item) => {
                processNestedExportColumn(
                  item,
                  column,
                  accumulator,
                  row.data,
                  experimentPrefix,
                );
              });
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
  }, [getDataForExport, columnsToExport, experiments, experimentsIds]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      const experimentsCount =
        experimentsIds.length || experiments?.length || 0;
      const fileName =
        experimentsCount === 1 && experiments?.[0]?.name
          ? experiments[0].name
          : `compare ${experimentsCount}`;
      return `${slugify(fileName, {
        lower: true,
      })}.${extension}`;
    },
    [experiments, experimentsIds],
  );

  return (
    <div className="flex items-center gap-2">
      <CompareExperimentsButton />
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
