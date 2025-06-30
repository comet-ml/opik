import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import capitalize from "lodash/capitalize";
import { MoreHorizontal, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { FEEDBACK_SCORE_TYPE, TraceFeedbackScore } from "@/types/traces";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

type CustomMeta = {
  onDelete: (name: string) => void;
  entityName: string;
};

const FeedbackScoreRowDeleteCell: React.FunctionComponent<
  CellContext<TraceFeedbackScore, unknown>
> = ({ column, row }) => {
  const { custom } = column.columnDef.meta ?? {};
  const { onDelete, entityName } = (custom ?? {}) as CustomMeta;
  const resetKeyRef = useRef(0);
  const feedbackScore = row.original;
  const actionName =
    feedbackScore.source === FEEDBACK_SCORE_TYPE.ui ? "clear" : "delete";

  const [open, setOpen] = useState<boolean | number>(false);

  const deleteFeedbackScore = useCallback(() => {
    onDelete(feedbackScore.name);
  }, [feedbackScore.name, onDelete]);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteFeedbackScore}
        title={`${capitalize(actionName)} feedback score`}
        description={`Are you sure you want to ${actionName} this feedback score from this ${entityName}?`}
        confirmText={`${capitalize(actionName)} feedback score`}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default FeedbackScoreRowDeleteCell;
