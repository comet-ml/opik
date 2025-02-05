import React, { useCallback, useRef, useState } from "react";
import { Split } from "lucide-react";
import last from "lodash/last";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/components/pages/CompareExperimentsPage/CompareExperimentsDialog";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import { ExperimentsCompare } from "@/types/datasets";

type CompareExperimentsActionsPanelProps = {
  rows?: ExperimentsCompare[];
  columnsToExport?: string[];
  experimentName?: string;
};

const CompareExperimentsActionsPanel: React.FC<
  CompareExperimentsActionsPanelProps
> = ({ rows = [], columnsToExport, experimentName }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !rows?.length;

  const mapRowData = useCallback(() => {
    if (!columnsToExport) return [];

    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        // we need split by dot to parse feedback_scores into correct structure
        const keys = column.split(".");
        const key = last(keys) as string;
        const keyPrefix = first(keys) as string;

        if (keyPrefix === "feedback_scores") {
          acc[`feedback_scores.${key}`] = get(
            row.experiment_items?.[0].feedback_scores?.find(
              (f) => f.name === key,
            ),
            "value",
            "-",
          );
        } else if (keyPrefix === "output") {
          acc[`dataset.${key}`] = get(
            row.experiment_items?.[0] ?? {},
            keys,
            "-",
          );
        } else if (keyPrefix === "created_at" || keyPrefix === "id") {
          acc[key] = get(row, keys, "");
        } else {
          acc[`evaluation_task.${key}`] = get(row.data, keys, "");
        }
        return acc;
      }, {});
    });
  }, [rows, columnsToExport]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(experimentName ?? "export", {
        lower: true,
      })}.${extension}`;
    },
    [experimentName],
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
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <Split className="mr-2 size-4" />
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
