import React, { useCallback, useRef, useState } from "react";
import { Database, Trash } from "lucide-react";
import last from "lodash/last";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  rows: Array<Trace | Span>;
  columnsToExport: string[];
  projectName: string;
  projectId: string;
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  rows,
  type,
  columnsToExport,
  projectName,
  projectId,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const tracesBatchDeleteMutation = useTracesBatchDeleteMutation();
  const disabled = !rows?.length;

  const deleteTracesHandler = useCallback(() => {
    tracesBatchDeleteMutation.mutate({
      projectId,
      ids: rows.map((row) => row.id),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, rows]);

  const mapRowData = useCallback(() => {
    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        // we need split by dot to parse usage into correct structure
        const keys = column.split(".");
        const key = last(keys) as string;
        const keyPrefix = first(keys) as string;

        if (keyPrefix === "feedback_scores") {
          acc[key] = get(
            row.feedback_scores?.find((f) => f.name === key),
            "value",
            "-",
          );
        } else {
          acc[key] = get(row, keys, "");
        }
        return acc;
      }, {});
    });
  }, [rows, columnsToExport]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(projectName, { lower: true })}-${
        type === TRACE_DATA_TYPE.traces ? "traces" : "llm-calls"
      }.${extension}`;
    },
    [projectName, type],
  );

  return (
    <div className="flex items-center gap-2">
      <AddToDatasetDialog
        key={`dataset-${resetKeyRef.current}`}
        rows={rows}
        open={open === 1}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={deleteTracesHandler}
        title="Delete traces"
        description="Are you sure you want to delete all selected traces?"
        confirmText="Delete traces"
      />
      <TooltipWrapper content="Add to dataset">
        <Button
          variant="outline"
          onClick={() => {
            setOpen(1);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Database className="mr-2 size-4" />
          Add to dataset
        </Button>
      </TooltipWrapper>
      <ExportToButton
        disabled={disabled || columnsToExport.length === 0}
        getData={mapRowData}
        generateFileName={generateFileName}
      />
      {type === TRACE_DATA_TYPE.traces && (
        <TooltipWrapper content="Delete">
          <Button
            variant="outline"
            size="icon"
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            disabled={disabled}
          >
            <Trash className="size-4" />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default TracesActionsPanel;
