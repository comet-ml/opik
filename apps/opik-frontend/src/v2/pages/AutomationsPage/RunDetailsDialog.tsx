import React, { useState } from "react";
import JsonView from "react18-json-view";
import useAutomationRunById from "@/api/automations/useAutomationRunById";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

const shortId = (id: string) => `${id.slice(0, 8)}…`;

type RunDetailsDialogProps = {
  runId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
};

// One run as the backend returns it. Parent and child runs open in place, since a
// parent may belong to another automation.
const RunDetailsDialog: React.FC<RunDetailsDialogProps> = ({
  runId: initialRunId,
  open,
  setOpen,
}) => {
  const [runId, setRunId] = useState(initialRunId);
  const { data: run, isPending, isError } = useAutomationRunById({ runId });
  const jsonViewTheme = useJsonViewTheme();

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Run {shortId(runId)}</DialogTitle>
          <DialogDescription>
            {run
              ? `${run.kind} · ${run.status}${
                  run.waiting_on ? ` · waiting on ${run.waiting_on}` : ""
                }`
              : runId}
          </DialogDescription>
        </DialogHeader>
        {isPending ? (
          <Loader className="min-h-40" />
        ) : isError || !run ? (
          <NoData className="min-h-40" message="This run could not be read." />
        ) : (
          <div className="flex flex-col gap-3">
            {(run.parent_run_id || (run.child_run_ids ?? []).length > 0) && (
              <div className="comet-body-xs flex flex-wrap items-center gap-2">
                {run.parent_run_id && (
                  <Button
                    variant="link"
                    size="2xs"
                    onClick={() => setRunId(run.parent_run_id!)}
                  >
                    Parent run {shortId(run.parent_run_id)}
                  </Button>
                )}
                {(run.child_run_ids ?? []).map((childId) => (
                  <Button
                    key={childId}
                    variant="link"
                    size="2xs"
                    onClick={() => setRunId(childId)}
                  >
                    Child run {shortId(childId)}
                  </Button>
                ))}
              </div>
            )}
            <div className="max-h-[60vh] overflow-auto rounded-md border border-border p-3">
              <JsonView src={run} {...jsonViewTheme} collapsed={2} />
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
};

export default RunDetailsDialog;
