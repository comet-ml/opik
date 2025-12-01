import React, { useCallback, useRef, useState } from "react";
import { Trash, Brain, Tag } from "lucide-react";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Thread } from "@/types/traces";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import AddToDropdown from "@/components/pages-shared/traces/AddToDropdown/AddToDropdown";
import RunEvaluationDialog from "@/components/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import AddTagDialog, {
  TAG_ENTITY_TYPE,
} from "@/components/pages-shared/traces/AddTagDialog/AddTagDialog";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { mapRowDataForExport } from "@/lib/traces/exportUtils";

type ThreadsActionsPanelProps = {
  getDataForExport: () => Promise<Thread[]>;
  selectedRows: Thread[];
  columnsToExport: string[];
  projectName: string;
  projectId: string;
};

const ThreadsActionsPanel: React.FunctionComponent<
  ThreadsActionsPanelProps
> = ({
  getDataForExport,
  selectedRows,
  columnsToExport,
  projectName,
  projectId,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useThreadBatchDeleteMutation();
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const deleteThreadsHandler = useCallback(() => {
    mutate({
      projectId,
      ids: selectedRows.map((row) => row.id),
    });
  }, [projectId, mutate, selectedRows]);

  const mapRowData = useCallback(async () => {
    const rows = await getDataForExport();
    return mapRowDataForExport(rows, columnsToExport);
  }, [getDataForExport, columnsToExport]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(projectName, { lower: true })}-threads.${extension}`;
    },
    [projectName],
  );

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={deleteThreadsHandler}
        title="Delete threads"
        description="Deleting threads will also remove all linked traces and their data. This action cannot be undone. Are you sure you want to continue?"
        confirmText="Delete threads"
        confirmButtonVariant="destructive"
      />
      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        rows={selectedRows}
        open={open === 3}
        setOpen={setOpen}
        projectId={projectId}
        type={TAG_ENTITY_TYPE.threads}
      />
      <RunEvaluationDialog
        key={`evaluation-${resetKeyRef.current}`}
        open={open === 4}
        setOpen={setOpen}
        projectId={projectId}
        entityIds={selectedRows.map((row) => row.thread_model_id)}
        entityType="thread"
      />
      <AddToDropdown
        getDataForExport={getDataForExport}
        selectedRows={selectedRows}
        disabled={disabled}
        dataType="threads"
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
    </div>
  );
};

export default ThreadsActionsPanel;
