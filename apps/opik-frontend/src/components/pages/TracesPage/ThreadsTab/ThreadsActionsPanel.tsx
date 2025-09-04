import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { Thread } from "@/types/traces";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";
import first from "lodash/first";

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
