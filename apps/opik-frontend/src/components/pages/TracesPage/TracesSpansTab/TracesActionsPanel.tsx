import React, { useState, useRef, useCallback } from "react";
import { Tag, Trash, Brain } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDropdown from "@/components/pages-shared/traces/AddToDropdown/AddToDropdown";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import AddTagDialog from "@/components/pages-shared/traces/AddTagDialog/AddTagDialog";
import RunEvaluationDialog from "@/components/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { mapRowDataForExport } from "@/lib/traces/exportUtils";
import slugify from "slugify";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  getDataForExport: () => Promise<Array<Trace | Span>>;
  selectedRows: Array<Trace | Span>;
  columnsToExport: string[];
  projectName: string;
  projectId: string;
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  getDataForExport,
  selectedRows,
  type,
  columnsToExport,
  projectName,
  projectId,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useTracesBatchDeleteMutation();
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const deleteTracesHandler = useCallback(() => {
    mutate({
      projectId,
      ids: selectedRows.map((row) => row.id),
    });
  }, [projectId, selectedRows, mutate]);

  const mapRowData = useCallback(async () => {
    const rows = await getDataForExport();
    return mapRowDataForExport(rows, columnsToExport);
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
      {type === TRACE_DATA_TYPE.spans && (
        <RunEvaluationDialog
          key={`evaluation-${resetKeyRef.current}`}
          open={open === 4}
          setOpen={setOpen}
          projectId={projectId}
          entityIds={selectedRows.map((row) => row.id)}
          entityType="span"
        />
      )}
      <AddToDropdown
        getDataForExport={getDataForExport}
        selectedRows={selectedRows}
        disabled={disabled}
        dataType={type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
      />
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
      {(type === TRACE_DATA_TYPE.traces || type === TRACE_DATA_TYPE.spans) && (
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
        disabled={disabled || columnsToExport.length === 0 || !isExportEnabled}
        getData={mapRowData}
        generateFileName={generateFileName}
        tooltipContent={
          !isExportEnabled
            ? "Export functionality is disabled for this installation"
            : undefined
        }
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
