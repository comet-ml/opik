import React, { useCallback, useRef, useState } from "react";
import last from "lodash/last";
import get from "lodash/get";
import { json2csv } from "json-2-csv";
import FileSaver from "file-saver";
import slugify from "slugify";

import { ArrowDownToLine, Database, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages/TracesPage/AddToDataset/AddToDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";

type TracesActionsButtonProps = {
  type: TRACE_DATA_TYPE;
  rows: Array<Trace | Span>;
  selectedColumns: string[];
  projectName: string;
  projectId: string;
};

const TracesActionsButton: React.FunctionComponent<
  TracesActionsButtonProps
> = ({ rows, type, selectedColumns, projectName, projectId }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const tracesBatchDeleteMutation = useTracesBatchDeleteMutation();

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
        acc[key] = get(row, keys, "");
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
    <>
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
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="default">
            {`Actions (${rows.length} selected)`}{" "}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Database className="mr-2 size-4" />
            Add to dataset
          </DropdownMenuItem>
          {type === TRACE_DATA_TYPE.traces && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
            >
              <Trash className="mr-2 size-4" />
              Delete
            </DropdownMenuItem>
          )}
          <DropdownMenuItem
            onClick={exportCSVHandler}
            disabled={selectedColumns.length === 0}
          >
            <ArrowDownToLine className="mr-2 size-4" />
            Export CSV
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default TracesActionsButton;
