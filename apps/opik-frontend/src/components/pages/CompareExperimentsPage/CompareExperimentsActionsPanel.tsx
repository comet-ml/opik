import React, { useCallback, useRef, useState } from "react";
import { Split } from "lucide-react";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";
import uniq from "lodash/uniq";

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
} from "@/types/shared";

export const EXPERIMENT_ITEM_FEEDBACK_SCORES_PREFIX = "feedback_scores";
export const EXPERIMENT_ITEM_OUTPUT_PREFIX = "output";

const processExperimentItems = (
  item: ExperimentItem,
  key: string,
  keys: string[],
  keyPrefix: string,
  accumulator: Record<string, unknown>,
  prefix: string = "",
) => {
  if (keyPrefix === EXPERIMENT_ITEM_FEEDBACK_SCORES_PREFIX) {
    const scoreObject = item.feedback_scores?.find((f) => f.name === key);
    accumulator[`${prefix}feedback_scores.${key}`] = get(
      scoreObject,
      "value",
      "-",
    );

    if (scoreObject && scoreObject.reason) {
      accumulator[`${prefix}feedback_scores.${key}_reason`] =
        scoreObject.reason;
    }
  } else {
    accumulator[`${prefix}dataset.${key}`] = get(item ?? {}, keys, "-");
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
          const keys = column.split(".");
          const key = column;
          const keyPrefix = first(keys) as string;

          if (
            keyPrefix === EXPERIMENT_ITEM_FEEDBACK_SCORES_PREFIX ||
            keyPrefix === EXPERIMENT_ITEM_OUTPUT_PREFIX ||
            keyPrefix === COLUMN_COMMENTS_ID
          ) {
            if (isCompare) {
              (row.experiment_items ?? []).forEach((item) => {
                const prefix = `${nameMap[item.experiment_id] ?? "unknown"}.`;
                processExperimentItems(
                  item,
                  key,
                  keys,
                  keyPrefix,
                  accumulator,
                  prefix,
                );
              });
            } else {
              const item = row.experiment_items?.[0];
              processExperimentItems(item, key, keys, keyPrefix, accumulator);
            }
          } else if (
            keyPrefix === COLUMN_CREATED_AT_ID ||
            keyPrefix === COLUMN_ID_ID
          ) {
            accumulator[key] = get(row, keys, "");
          } else {
            accumulator[`evaluation_task.${key}`] = get(row.data, keys, "");
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
