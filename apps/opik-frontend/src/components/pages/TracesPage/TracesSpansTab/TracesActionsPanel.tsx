import React, { useState, useRef, useCallback } from "react";
import { Database, Tag, Trash } from "lucide-react";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import AddTagDialog from "@/components/pages-shared/traces/AddTagDialog/AddTagDialog";

import { PencilLine } from "lucide-react";
import { MessageSquarePlus } from "lucide-react";
import BatchAnnotateDialog from "@/components/pages-shared/traces/BatchAnnotateDialog/BatchAnnotateDialog";
import BatchCommentDialog from "@/components/pages-shared/traces/BatchCommentDialog/BatchCommentDialog";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  rows: Array<Trace | Span>;
  columnsToExport: string[];
  projectName: string;
  projectId: string;
  onClearSelection?: () => void;
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  rows,
  type,
  columnsToExport,
  projectName,
  projectId,
  onClearSelection,
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
  }, [projectId, rows]);

  const mapRowData = useCallback(() => {
    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
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
        description="Deleting these traces will also remove their data from related experiment samples. This action cannot be undone. Are you sure you want to continue?"
        confirmText="Delete traces"
        confirmButtonVariant="destructive"
      />
      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        rows={rows}
        open={open === 3}
        setOpen={setOpen}
        projectId={projectId}
        type={type}
        onSuccess={onClearSelection}
      />
      <BatchAnnotateDialog
        key={`annotate-${resetKeyRef.current}`}
        rows={rows}
        type={type}
        open={open === 4}
        setOpen={setOpen}
        projectId={projectId}
        onSuccess={onClearSelection}
      />
      <BatchCommentDialog
        key={`comment-${resetKeyRef.current}`}
        rows={rows}
        type={type}
        projectId={projectId}
        open={open === 5}
        setOpen={setOpen}
        onSuccess={onClearSelection}
      />
      <TooltipWrapper content="Add to dataset">
        <Button
          variant="outline"
          size="sm"
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
      <TooltipWrapper content="Add tags">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(3);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag className="mr-2 size-4" />
          Add tags
        </Button>
      </TooltipWrapper>
      <TooltipWrapper content="Annotate">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(4);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <PencilLine className="mr-2 size-4" />
          Annotate
        </Button>
      </TooltipWrapper>

      <TooltipWrapper content="Comment">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(5);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <MessageSquarePlus className="mr-2 size-4" />
          Comment
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
  );
};

export default TracesActionsPanel;