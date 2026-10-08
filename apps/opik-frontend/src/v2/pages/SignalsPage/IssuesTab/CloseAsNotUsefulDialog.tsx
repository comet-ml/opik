import React, { useEffect, useState } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";

export const CLOSE_NOTE_MAX_LENGTH = 500;

type CloseAsNotUsefulDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: (closeNote: string) => void;
  isPending?: boolean;
};

const CloseAsNotUsefulDialog: React.FC<CloseAsNotUsefulDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  isPending = false,
}) => {
  const [note, setNote] = useState("");

  useEffect(() => {
    if (open) setNote("");
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg gap-1.5 p-5 sm:max-w-screen-sm">
        <DialogHeader className="pb-0">
          <DialogTitle className="text-base">
            Close issue as Not useful
          </DialogTitle>
          <DialogDescription className="text-muted-slate">
            This issue will move to Closed issues, and Diagnostics will try not
            to raise it or similar findings in future runs. You can reopen it at
            any time.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-1 pt-3">
          <Label
            htmlFor="closeIssueNote"
            className="comet-body-s-accented px-0.5 pb-0.5"
          >
            Why isn&apos;t it useful? (Optional)
          </Label>
          <Textarea
            id="closeIssueNote"
            className="comet-body-s h-[90px] min-h-0 resize-none"
            placeholder="Diagnostics uses this to avoid similar issues"
            value={note}
            maxLength={CLOSE_NOTE_MAX_LENGTH}
            onChange={(e) => setNote(e.target.value)}
          />
        </div>
        <DialogFooter className="pt-4">
          <Button
            variant="outline"
            size="sm"
            disabled={isPending}
            onClick={() => setOpen(false)}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={isPending}
            onClick={() => onConfirm(note.trim())}
          >
            Close issue as Not useful
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default CloseAsNotUsefulDialog;
