import React, { useState, useRef, useCallback } from "react";
import { Tag, Trash } from "lucide-react";
import slugify from "slugify";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import AddToDropdown from "@/v2/pages-shared/traces/AddToDropdown/AddToDropdown";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useTracesBatchDeleteMutation from "@/api/traces/useTraceBatchDeleteMutation";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
import AddTagDialog from "@/v2/pages-shared/traces/AddTagDialog/AddTagDialog";
import EvaluateButton from "@/v2/pages-shared/automations/EvaluateButton/EvaluateButton";
import RunEvaluationDialog from "@/v2/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import useFilteredRulesList from "@/api/automations/useFilteredRulesList";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { mapRowDataForExport } from "@/lib/traces/exportUtils";
import { usePermissions } from "@/contexts/PermissionsContext";

type TracesActionsPanelProps = {
  type: TRACE_DATA_TYPE;
  getDataForExport: () => Promise<Array<Trace | Span>>;
  selectedRows: Array<Trace | Span>;
  columnsToExport: string[];
  projectName: string;
  projectId: string;
  hideEvaluate?: boolean;
  buttonVariant?: "outline" | "ghost" | "ghostInverted";
};

const TracesActionsPanel: React.FunctionComponent<TracesActionsPanelProps> = ({
  getDataForExport,
  selectedRows,
  type,
  columnsToExport,
  projectName,
  projectId,
  hideEvaluate = false,
  buttonVariant = "outline",
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useTracesBatchDeleteMutation();
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const {
    permissions: { canDeleteTraces },
  } = usePermissions();

  const showEvaluate =
    type === TRACE_DATA_TYPE.traces || type === TRACE_DATA_TYPE.spans;
  const entityType =
    type === TRACE_DATA_TYPE.traces ? "trace" : ("span" as const);

  const enableEvaluate = showEvaluate && !hideEvaluate;

  const { rules, isLoading: isRulesLoading } = useFilteredRulesList({
    projectId,
    entityType,
    enabled: enableEvaluate,
  });

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
      {canDeleteTraces && (
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
      )}
      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        rows={selectedRows}
        open={open === 3}
        setOpen={setOpen}
        projectId={projectId}
        type={type}
      />
      {enableEvaluate && (
        <RunEvaluationDialog
          key={`evaluation-${resetKeyRef.current}`}
          open={open === 4}
          setOpen={setOpen}
          projectId={projectId}
          entityIds={selectedRows.map((row) => row.id)}
          entityType={entityType}
          rules={rules}
          isLoading={isRulesLoading}
        />
      )}
      <AddToDropdown
        getDataForExport={getDataForExport}
        selectedRows={selectedRows}
        disabled={disabled}
        dataType={type === TRACE_DATA_TYPE.traces ? "traces" : "spans"}
        buttonVariant={buttonVariant}
      />
      <TooltipWrapper content="Manage tags">
        <Button
          variant={buttonVariant}
          size="sm"
          onClick={() => {
            setOpen(3);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag className="mr-1.5 size-3.5" />
          <span>Manage tags</span>
        </Button>
      </TooltipWrapper>
      {enableEvaluate && (
        <EvaluateButton
          isNoRules={!rules?.length}
          disabled={disabled}
          buttonVariant={buttonVariant}
          label="Evaluate"
          onClick={() => {
            setOpen(4);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        />
      )}
      <Separator
        orientation="vertical"
        className={cn(
          "mx-1 h-4 opacity-50",
          buttonVariant === "ghostInverted" && "bg-white",
        )}
      />
      <ExportToButton
        disabled={disabled || columnsToExport.length === 0 || !isExportEnabled}
        getData={mapRowData}
        generateFileName={generateFileName}
        buttonVariant={buttonVariant}
        tooltipContent={
          !isExportEnabled
            ? "Export functionality is disabled for this installation"
            : undefined
        }
      />
      {type === TRACE_DATA_TYPE.traces && canDeleteTraces && (
        <TooltipWrapper content="Delete">
          <Button
            variant={buttonVariant}
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
