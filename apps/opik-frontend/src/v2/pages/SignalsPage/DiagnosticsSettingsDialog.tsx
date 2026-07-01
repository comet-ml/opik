import React, { useEffect, useState } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Switch } from "@/ui/switch";
import { Separator } from "@/ui/separator";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

type DiagnosticsSettingsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  enabled: boolean;
};

const DiagnosticsSettingsDialog: React.FC<DiagnosticsSettingsDialogProps> = ({
  open,
  setOpen,
  projectId,
  enabled,
}) => {
  const [on, setOn] = useState(enabled);
  const updateMutation = useUpdateAgentInsightsJobMutation();

  useEffect(() => {
    if (open) setOn(enabled);
  }, [open, enabled]);

  const handleSave = () => {
    if (on !== enabled) {
      trackEvent(
        on
          ? OpikEvent.DIAGNOSTICS_AUTO_ENABLED
          : OpikEvent.DIAGNOSTICS_AUTO_DISABLED,
        { project_id: projectId, source: "settings" },
      );
    }
    updateMutation.mutate(
      {
        projectId,
        status: on
          ? AGENT_INSIGHTS_JOB_STATUS.enabled
          : AGENT_INSIGHTS_JOB_STATUS.disabled,
      },
      { onSuccess: () => setOpen(false) },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>Diagnostics settings</DialogTitle>
        </DialogHeader>

        <div className="flex items-center gap-3 py-2">
          <Switch checked={on} onCheckedChange={setOn} />
          <div className="flex flex-col gap-0.5">
            <span className="comet-body-s text-foreground">
              Daily diagnostics on
            </span>
            <span className="comet-body-s text-light-slate">
              Turn off to pause automated diagnostics. You can reactivate at any
              time.
            </span>
          </div>
        </div>

        <Separator />

        <div className="flex flex-col gap-1 py-2">
          <span className="comet-body-s-accented text-foreground">Billing</span>
          <span className="comet-body-s text-muted-slate">
            Runs on your Ollie tokens.{" "}
            {/* TODO: wire to the Ollie/Comet billing page */}
            <button
              type="button"
              className="text-foreground underline underline-offset-2 hover:text-primary"
            >
              View billing
            </button>
          </span>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={updateMutation.isPending}>
            Save changes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DiagnosticsSettingsDialog;
