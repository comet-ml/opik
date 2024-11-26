import React, { useCallback, useRef, useState } from "react";
import last from "lodash/last";
import get from "lodash/get";
import { first } from "lodash";
import { json2csv } from "json-2-csv";
import FileSaver from "file-saver";
import slugify from "slugify";

import { Database, Download, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages/TracesPage/AddToDataset/AddToDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  rows: Array<Trace | Span>;
  selectedColumns: string[];
  projectName: string;
  projectId: string;
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  rows,
  type,
  selectedColumns,
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

  const exportCSVHandler = useCallback(() => {
    const fileName = `${slugify(projectName, { lower: true })}-${
      type === TRACE_DATA_TYPE.traces ? "traces" : "llm-calls"
    }.csv`;
    const mappedRows = rows.map((row) => {
      return selectedColumns.reduce<Record<string, unknown>>((acc, column) => {
        // we need split by dot to parse usage into correct structure
        const keys = column.split(".");
        const key = last(keys) as string;
        const keyPrefix = first(keys) as string;

        if (keyPrefix === "feedback_scores") {
          acc[key] = (row.feedback_scores?.find((f) => f.name === key) ?? {
            value: "-",
          })["value"];
        } else {
          acc[key] = get(row, keys, "");
        }
        return acc;
      }, {});
    });

    FileSaver.saveAs(
      new Blob([json2csv(mappedRows, { arrayIndexesAsKeys: true })], {
        type: "text/csv;charset=utf-8",
      }),
      fileName,
    );
  }, [projectName, rows, selectedColumns, type]);

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
      <TooltipWrapper content="Export CSV">
        <Button
          variant="outline"
          size="icon"
          onClick={exportCSVHandler}
          disabled={disabled || selectedColumns.length === 0}
        >
          <Download className="size-4" />
        </Button>
      </TooltipWrapper>
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
