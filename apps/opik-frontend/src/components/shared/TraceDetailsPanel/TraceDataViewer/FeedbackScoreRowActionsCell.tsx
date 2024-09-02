import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { FEEDBACK_SCORE_TYPE, TraceFeedbackScore } from "@/types/traces";
import capitalize from "lodash/capitalize";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";

type CustomMeta = {
  traceId: string;
  spanId?: string;
};

const FeedbackScoreRowActionsCell: React.FunctionComponent<
  CellContext<TraceFeedbackScore, unknown>
> = ({ column, row }) => {
  const { custom } = column.columnDef.meta ?? {};
  const { traceId, spanId } = (custom ?? {}) as CustomMeta;
  const resetKeyRef = useRef(0);
  const feedbackScore = row.original;
  const actionName =
    feedbackScore.source === FEEDBACK_SCORE_TYPE.ui ? "clear" : "delete";

  const [open, setOpen] = useState<boolean | number>(false);

  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const deleteFeedbackScore = useCallback(() => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name: feedbackScore.name,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackScore.name, traceId, spanId]);

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
        description={`Are you sure you want to ${actionName} this feedback score from this ${
          spanId ? "span" : "trace"
        }?`}
        confirmText={`${capitalize(actionName)} feedback score`}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">Open menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            data-testid="feedback-score-delete-button"
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            {capitalize(actionName)}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
};

export default FeedbackScoreRowActionsCell;
