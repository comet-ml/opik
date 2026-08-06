import React, { useState, useCallback } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { Span, Trace } from "@/types/traces";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";

type BatchAnnotateDialogProps = {
  rows: Array<Trace | Span>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  projectId: string;
};

const BatchAnnotateDialog: React.FunctionComponent<BatchAnnotateDialogProps> = ({
  rows,
  open,
  setOpen,
}) => {
  const [name, setName] = useState<string>("user_feedback");
  const [value, setValue] = useState<string>("1.0");
  const [reason, setReason] = useState<string>("");
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const { mutateAsync: setFeedbackScore } = useTraceFeedbackScoreSetMutation();

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      setOpen(isOpen);
    },
    [setOpen],
  );

  const handleAnnotate = useCallback(async () => {
    if (!name.trim() || isNaN(parseFloat(value))) {
      return;
    }

    setIsSubmitting(true);
    try {
      const numericValue = parseFloat(value);
      for (const row of rows) {
        await setFeedbackScore({
          traceId: row.id,
          name: name.trim(),
          value: numericValue,
          reason: reason.trim() || undefined,
        });
      }
      setOpen(false);
    } catch {
      // Error handled by mutation toast
    } finally {
      setIsSubmitting(false);
    }
  }, [name, value, reason, rows, setFeedbackScore, setOpen]);

  const isOpen = Boolean(open);

  return (
    <Dialog open={isOpen} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>Annotate {rows.length} Traces</DialogTitle>
          <DialogDescription>
            Add a feedback score and reason to all {rows.length} selected traces at once.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="score-name">Score Name</Label>
            <Input
              id="score-name"
              placeholder="e.g. accuracy, hallucination, user_feedback"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="score-value">Score Value (0 to 1)</Label>
            <Input
              id="score-value"
              type="number"
              step="0.1"
              min="0"
              max="1"
              placeholder="1.0"
              value={value}
              onChange={(e) => setValue(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="score-reason">Reason (Optional)</Label>
            <Textarea
              id="score-reason"
              placeholder="Enter explanation for this score..."
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
            />
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isSubmitting}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            type="button"
            onClick={handleAnnotate}
            disabled={isSubmitting || !name.trim() || isNaN(parseFloat(value))}
          >
            {isSubmitting ? "Annotating..." : `Annotate ${rows.length} Traces`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default BatchAnnotateDialog;
