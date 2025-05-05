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
} from "@/types/shared";
import { EXPERIMENT_ITEM_OUTPUT_PREFIX } from "@/constants/experiments";

const EVALUATION_EXPORT_COLUMNS = [
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  COLUMN_COMMENTS_ID,
];
const FLAT_COLUMNS = [COLUMN_CREATED_AT_ID, COLUMN_ID_ID];

const processNestedExportColumn = (
  item: ExperimentItem,
  column: string,
  accumulator: Record<string, unknown>,
  prefix: string = "",
) => {
  const keys = column.split(".");
  const prefixColumnKey = first(keys) as string;

  if (prefixColumnKey === COLUMN_FEEDBACK_SCORES_ID) {
    const scoreName = column.replace(`${prefixColumnKey}.`, "");
    const scoreObject = item.feedback_scores?.find((f) => f.name === scoreName);
    accumulator[`${prefix}${column}`] = get(scoreObject, "value", "-");

    if (scoreObject?.reason) {
      accumulator[`${prefix}${column}_reason`] = scoreObject.reason;
    }

    return;
  }

  if (EVALUATION_EXPORT_COLUMNS.includes(prefixColumnKey)) {
    const evaluationName = column.replace(`${prefixColumnKey}.`, "");
    accumulator[`${prefix}evaluation_task.${evaluationName}`] = get(
      item ?? {},
      keys,
      "-",
    );

    return;
  }
};

type CompareExperimentsActionsPanelProps = {
  rows?: ExperimentsCompare[];
  columnsToExport?: string[];
  experiments?: Experiment[];
};

const CompareExperimentsActionsPanel: React.FC<
  CompareExperimentsActionsPanelProps
> = ({ rows = [], columnsToExport, experiments }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !rows?.length;

  const mapRowData = useCallback(() => {
    if (!columnsToExport) return [];

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
            accumulator[`dataset.${column}`] = get(row.data, column, "-");

            return accumulator;
          }

          if (isCompare) {
            (row.experiment_items ?? []).forEach((item) => {
              const prefix = `${nameMap[item.experiment_id] ?? "unknown"}.`;
              processNestedExportColumn(item, column, accumulator, prefix);
            });
          } else {
            const item = row.experiment_items?.[0];
            processNestedExportColumn(item, column, accumulator);
          }

          return accumulator;
        },
        {},
      );
    });
  }, [rows, columnsToExport, experiments]);

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
      <TooltipWrapper content="Compare experiments">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <Split className="mr-2 size-3.5" />
          Compare
        </Button>
      </TooltipWrapper>
      {columnsToExport && (
        <ExportToButton
          disabled={disabled || columnsToExport.length === 0}
          getData={mapRowData}
          generateFileName={generateFileName}
        />
      )}
    </div>
  );
};

export default CompareExperimentsActionsPanel;
