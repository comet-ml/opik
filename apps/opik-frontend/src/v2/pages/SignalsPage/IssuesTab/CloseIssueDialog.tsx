import React, { useLayoutEffect, useRef, useState } from "react";
import * as RadioGroupPrimitive from "@radix-ui/react-radio-group";
import { CircleCheck, LucideIcon, ThumbsDown } from "lucide-react";
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
import { Separator } from "@/ui/separator";
import { Textarea } from "@/ui/textarea";
import { cn } from "@/lib/utils";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";

export const CLOSE_NOTE_MAX_LENGTH = 500;

export type CloseIssueStatus =
  | AGENT_INSIGHTS_ISSUE_STATUS.resolved
  | AGENT_INSIGHTS_ISSUE_STATUS.closed;

const OPTIONS: {
  value: CloseIssueStatus;
  icon: LucideIcon;
  label: string;
  description: string;
}[] = [
  {
    value: AGENT_INSIGHTS_ISSUE_STATUS.resolved,
    icon: CircleCheck,
    label: "Resolved",
    description:
      "The issue has been fixed. New occurrences are still tracked, but it won’t reopen automatically.",
  },
  {
    value: AGENT_INSIGHTS_ISSUE_STATUS.closed,
    icon: ThumbsDown,
    label: "Not useful",
    description:
      "The issue isn’t helpful. Diagnostics will try not to raise it or similar findings in future runs.",
  },
];

type CloseIssueDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  issueName: string;
  // closeNote is only sent with Not useful.
  onConfirm: (status: CloseIssueStatus, closeNote?: string) => void;
  isPending?: boolean;
};

const CloseIssueDialog: React.FC<CloseIssueDialogProps> = ({
  open,
  setOpen,
  issueName,
  onConfirm,
  isPending = false,
}) => {
  const [status, setStatus] = useState<CloseIssueStatus>(
    AGENT_INSIGHTS_ISSUE_STATUS.resolved,
  );
  const [note, setNote] = useState("");
  // Every open starts on Resolved, so focus goes there by value, not by whatever was selected last.
  const resolvedRef = useRef<HTMLButtonElement>(null);

  // Before paint, so a reopened dialog never flashes the previous choice.
  useLayoutEffect(() => {
    if (open) {
      setStatus(AGENT_INSIGHTS_ISSUE_STATUS.resolved);
      setNote("");
    }
  }, [open]);

  const isNotUseful = status === AGENT_INSIGHTS_ISSUE_STATUS.closed;

  const submit = () => {
    if (isPending) return;
    onConfirm(status, isNotUseful ? note.trim() : undefined);
  };

  // Enter submits; inside the reason field Enter is a new line and Cmd/Ctrl+Enter
  // submits, like the other forms with a text area. Enter on Cancel or the close
  // button keeps its own action.
  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key !== "Enter" || event.shiftKey) return;
    const target = event.target as HTMLElement;
    if (target instanceof HTMLTextAreaElement) {
      if (!event.metaKey && !event.ctrlKey) return;
    } else if (
      target instanceof HTMLButtonElement &&
      target.getAttribute("role") !== "radio"
    ) {
      return;
    }
    event.preventDefault();
    submit();
  };

  return (
    <Dialog
      open={open}
      // While the close is saving, Esc / outside click / X can't drop the choice and note.
      onOpenChange={(next) => (next || !isPending) && setOpen(next)}
    >
      <DialogContent
        className="max-w-lg gap-1.5 p-5 sm:max-w-screen-sm"
        onKeyDown={handleKeyDown}
        // Start on the selected option (not Cancel), so Enter right away closes the issue.
        onOpenAutoFocus={(event) => {
          event.preventDefault();
          resolvedRef.current?.focus();
        }}
      >
        <DialogHeader className="pb-0">
          <DialogTitle className="text-base leading-5">Close issue</DialogTitle>
          <DialogDescription className="text-muted-slate">
            {issueName} will move to Closed issues. You can reopen it at any
            time.
          </DialogDescription>
        </DialogHeader>
        <RadioGroupPrimitive.Root
          aria-label="Why are you closing this issue?"
          className="flex flex-col gap-3 pt-2"
          value={status}
          onValueChange={(value) => setStatus(value as CloseIssueStatus)}
        >
          {OPTIONS.map(({ value, icon: Icon, label, description }) => {
            const selected = value === status;
            return (
              <div
                key={value}
                // The whole card selects, not only the radio inside its padding.
                onClick={() => setStatus(value)}
                className={cn(
                  // Figma strokes sit inside the 12/8 padding.
                  "flex flex-col gap-2 rounded-md border px-[11px] py-[7px]",
                  selected
                    ? "border-primary bg-primary-100"
                    : "border-border bg-background",
                )}
              >
                <RadioGroupPrimitive.Item
                  ref={
                    value === AGENT_INSIGHTS_ISSUE_STATUS.resolved
                      ? resolvedRef
                      : undefined
                  }
                  value={value}
                  className="flex flex-col items-start gap-0.5 text-left focus:outline-none focus-visible:rounded-sm focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <span className="comet-body-s-accented flex h-5 items-center gap-1 pr-2 text-foreground">
                    <Icon
                      className={cn(
                        "size-3.5",
                        selected ? "text-primary" : "text-muted-slate",
                      )}
                    />
                    {label}
                  </span>
                  <span className="comet-body-xs text-muted-slate">
                    {description}
                  </span>
                </RadioGroupPrimitive.Item>
                {value === AGENT_INSIGHTS_ISSUE_STATUS.closed && selected && (
                  <>
                    <div className="p-1">
                      <Separator />
                    </div>
                    <div className="flex flex-col gap-0.5 pb-1.5">
                      <Label
                        htmlFor="closeIssueNote"
                        className="comet-body-xs-accented px-0.5 pb-0.5"
                      >
                        Why isn&apos;t it useful? (Optional)
                      </Label>
                      <Textarea
                        id="closeIssueNote"
                        className="h-[102px] min-h-0 resize-none text-xs leading-4"
                        placeholder="Diagnostics uses this to avoid similar issues"
                        value={note}
                        maxLength={CLOSE_NOTE_MAX_LENGTH}
                        onChange={(e) => setNote(e.target.value)}
                      />
                    </div>
                  </>
                )}
              </div>
            );
          })}
        </RadioGroupPrimitive.Root>
        <DialogFooter className="pt-3">
          <Button
            variant="outline"
            size="sm"
            disabled={isPending}
            onClick={() => setOpen(false)}
          >
            Cancel
          </Button>
          <Button size="sm" disabled={isPending} onClick={submit}>
            Close issue
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default CloseIssueDialog;
