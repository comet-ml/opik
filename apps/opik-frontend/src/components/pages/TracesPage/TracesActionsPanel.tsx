import React, { useCallback, useRef, useState } from "react";
import { Database, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/pages/TracesPage/ExportToButton";

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
        type={type}
        columnsToExport={columnsToExport}
        rows={rows}
        projectName={projectName}
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
