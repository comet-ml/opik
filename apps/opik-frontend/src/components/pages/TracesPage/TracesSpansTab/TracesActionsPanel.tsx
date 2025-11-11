import React, { useState, useRef, useCallback, useMemo } from "react";
import { Tag, Trash, Brain } from "lucide-react";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDropdown from "@/components/pages-shared/traces/AddToDropdown/AddToDropdown";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import AddTagDialog from "@/components/pages-shared/traces/AddTagDialog/AddTagDialog";
import { ResponsiveToolbarProvider } from "@/contexts/ResponsiveToolbarContext";
import { ResponsiveButton } from "@/components/ui/ResponsiveButton";
import RunEvaluationDialog from "@/components/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  getDataForExport: () => Promise<Array<Trace | Span>>;
  selectedRows: Array<Trace | Span>;
  columnsToExport: string[];
  projectName: string;
  projectId: string;
  onClearSelection?: () => void;
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  getDataForExport,
  selectedRows,
  type,
  columnsToExport,
  projectName,
  projectId,
  onClearSelection,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useTracesBatchDeleteMutation();
  const disabled = !selectedRows?.length;

  const toolbarElements = useMemo(
    () => [
      { name: "ADD_TO", size: 90, visible: true },
      { name: "GAP", size: 8, visible: true },
      { name: "ADD_TAGS", size: 118, visible: true },
      { name: "GAP", size: 8, visible: true },
      { name: "EVALUATE", size: 118, visible: type === TRACE_DATA_TYPE.traces },
      { name: "GAP", size: 8, visible: type === TRACE_DATA_TYPE.traces },
      { name: "EXPORT", size: 40, visible: true },
      { name: "GAP", size: 8, visible: true },
      { name: "DELETE", size: 40, visible: type === TRACE_DATA_TYPE.traces },
    ],
    [type],
  );

  const deleteTracesHandler = useCallback(() => {
    mutate({
      projectId,
      ids: selectedRows.map((row) => row.id),
    });
  }, [projectId, selectedRows, mutate]);

  const mapRowData = useCallback(async () => {
    const rows = await getDataForExport();
    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        // we need split by dot to parse feedback_scores into correct structure
        const keys = column.split(".");
        const keyPrefix = first(keys) as string;

        if (keyPrefix === COLUMN_FEEDBACK_SCORES_ID) {
          const scoreName = column.replace(`${COLUMN_FEEDBACK_SCORES_ID}.`, "");
          const scoreObject = row.feedback_scores?.find(
            (f) => f.name === scoreName,
          );
          acc[column] = get(scoreObject, "value", "-");

          if (scoreObject && scoreObject.reason) {
            acc[`${column}_reason`] = scoreObject.reason;
          }
        } else {
          acc[column] = get(row, keys, "");
        }

        return acc;
      }, {});
    });
  }, [getDataForExport, columnsToExport]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(projectName, { lower: true })}-${
        type === TRACE_DATA_TYPE.traces ? "traces" : "llm-calls"
      }.${extension}`;
    },
    [projectName, type],
  );

  return (
    <ResponsiveToolbarProvider elements={toolbarElements}>
      <div className="flex items-center gap-2">
        <ConfirmDialog
          key={`delete-${resetKeyRef.current}`}
          open={open === 2}
          setOpen={setOpen}
          onConfirm={deleteTracesHandler}
          title="Delete traces"
          description="Deleting these traces will also remove their data from related experiment samples. This action cannot be undone. Are you sure you want to continue?"
          confirmText="Delete traces"
          confirmButtonVariant="destructive"
        />
        <AddTagDialog
          key={`tag-${resetKeyRef.current}`}
          rows={selectedRows}
          open={open === 3}
          setOpen={setOpen}
          projectId={projectId}
          type={type}
          onSuccess={onClearSelection}
        />
        {type === TRACE_DATA_TYPE.traces && (
          <RunEvaluationDialog
            key={`evaluation-${resetKeyRef.current}`}
            open={open === 4}
            setOpen={setOpen}
            projectId={projectId}
            entityIds={selectedRows.map((row) => row.id)}
            entityType="trace"
          />
        )}
        <AddToDropdown
          getDataForExport={getDataForExport}
          selectedRows={selectedRows}
          disabled={disabled}
          dataType={type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
        />
        <ResponsiveButton
          text="Add tags"
          icon={<Tag />}
          variant="outline"
          onClick={() => {
            setOpen(3);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        />
        {type === TRACE_DATA_TYPE.traces && (
          <TooltipWrapper content="Evaluate">
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setOpen(4);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Brain className="mr-2 size-4" />
              Evaluate
            </Button>
          </TooltipWrapper>
        )}
        <ExportToButton
          disabled={disabled || columnsToExport.length === 0}
          getData={mapRowData}
          generateFileName={generateFileName}
        />
        {type === TRACE_DATA_TYPE.traces && (
          <TooltipWrapper content="Delete">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Trash />
            </Button>
          </TooltipWrapper>
        )}
      </div>
    </ResponsiveToolbarProvider>
  );
};

export default TracesActionsPanel;
