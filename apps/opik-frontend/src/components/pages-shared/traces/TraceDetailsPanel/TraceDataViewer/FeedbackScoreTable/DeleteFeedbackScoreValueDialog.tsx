import React from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ExpandingFeedbackScoreRow } from "./types";
import { useFeedbackScoreDeletePreference } from "./hooks/useFeedbackScoreDeletePreference";
import { Label } from "@/components/ui/label";

type SetInactiveConfirmDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onDeleteFeedbackScore: (
    name: string,
    author?: string,
    spanId?: string,
  ) => void;
  entityType: "trace" | "thread" | "span" | "experiment";
  row: ExpandingFeedbackScoreRow;
};

const entityTypeMap: Record<string, string> = {
  trace: "a trace",
  thread: "a thread",
  span: "a span",
  experiment: "an experiment",
};

const DeleteFeedbackScoreValueDialog: React.FunctionComponent<
  SetInactiveConfirmDialogProps
> = ({ open, setOpen, onDeleteFeedbackScore, row, entityType }) => {
  const [dontAskAgain, setDontAskAgain] = useFeedbackScoreDeletePreference();

  const onConfirm = () => {
    onDeleteFeedbackScore(row.name, row.author, row.span_id);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Remove feedback score</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <div className="comet-body-s text-muted-slate">
            Removing a feedback score from {entityTypeMap[entityType]}{" "}
            can&apos;t be undone. Are you sure you want to continue?
          </div>
          <Label
            key="dont-ask-again"
            className="flex cursor-pointer items-center gap-2"
          >
            <Checkbox
              id="dont-ask-again"
              checked={dontAskAgain}
              onCheckedChange={(v) => setDontAskAgain(v === true)}
            />

            <div className="comet-body-s text-muted-slate">
              Don&apos;t ask me again
            </div>
          </Label>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" variant="destructive" onClick={onConfirm}>
              Remove score
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DeleteFeedbackScoreValueDialog;
