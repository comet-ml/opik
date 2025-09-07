import React, { useState, useRef, useCallback } from "react";
import { Database, Tag, Trash, Users, ChevronDown } from "lucide-react";
import first from "lodash/first";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Span, Trace } from "@/types/traces";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import AddToQueueDialog from "@/components/pages-shared/traces/AddToQueueDialog/AddToQueueDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import AddTagDialog from "@/components/pages-shared/traces/AddTagDialog/AddTagDialog";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

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
  
  const annotationQueuesEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ANNOTATION_QUEUES_ENABLED
  );

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
      <AddToQueueDialog
        key={`queue-${resetKeyRef.current}`}
        rows={rows}
        open={open === 2}
        setOpen={setOpen}
        type={type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 3}
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
        open={open === 4}
        setOpen={setOpen}
        projectId={projectId}
        type={type}
        onSuccess={onClearSelection}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            disabled={disabled}
          >
            Add to
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="w-60">
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            disabled={disabled}
          >
            <Database className="mr-2 size-4" />
            Add to dataset
          </DropdownMenuItem>
          {annotationQueuesEnabled && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              disabled={disabled}
            >
              <Users className="mr-2 size-4" />
              Add to Annotation Queue
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <TooltipWrapper content="Add tags">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(4);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag className="mr-2 size-4" />
          Add tags
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
              setOpen(3);
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
