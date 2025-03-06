import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import last from "lodash/last";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Thread } from "@/types/traces";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";

type ThreadsActionsPanelProps = {
  rows: Thread[];
  columnsToExport: string[];
  projectName: string;
  projectId: string;
};

const ThreadsActionsPanel: React.FunctionComponent<
  ThreadsActionsPanelProps
> = ({ rows, columnsToExport, projectName, projectId }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useThreadBatchDeleteMutation();
  const disabled = !rows?.length;

  const deleteThreadsHandler = useCallback(() => {
    mutate({
      projectId,
      ids: rows.map((row) => row.id),
    });
  }, [projectId, mutate, rows]);

  const mapRowData = useCallback(() => {
    return rows.map((row) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        const keys = column.split(".");
        const key = last(keys) as string;
        acc[key] = get(row, keys, "");

        return acc;
      }, {});
    });
  }, [rows, columnsToExport]);

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
        description="Are you sure you want to delete all selected threads?"
        confirmText="Delete threads"
      />
      <ExportToButton
        disabled={disabled || columnsToExport.length === 0}
        getData={mapRowData}
        generateFileName={generateFileName}
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
