import React from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { buildDocsUrl } from "@/v2/lib/utils";

// Default daily cron time (config.yml `agentInsightsReport.schedule` =
// `0 5 0 * * ?`). Shown for context; the actual time is set per deployment.
const DAILY_RUN_TIME_LABEL = "00:05 UTC";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

type TurnOnDiagnosticDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
  isPending: boolean;
};

const TurnOnDiagnosticDialog: React.FC<TurnOnDiagnosticDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  isPending,
}) => {
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Turn on daily diagnostic?</DialogTitle>
        </DialogHeader>

        <div className="comet-body-s text-muted-slate">
          First diagnostic runs now. Then every day at {DAILY_RUN_TIME_LABEL} —
          we&apos;ll review your traces and flag issues to fix. Runs on your
          Ollie tokens. Turn off anytime.{" "}
          <a
            href={DIAGNOSTICS_DOCS_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[var(--color-primary)] hover:underline"
          >
            Learn more
          </a>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={isPending}>
            Run first diagnostic
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default TurnOnDiagnosticDialog;
