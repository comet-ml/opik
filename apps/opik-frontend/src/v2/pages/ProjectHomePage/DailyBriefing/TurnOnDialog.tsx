import { useState } from "react";
import { Button } from "@/ui/button";
import { Switch } from "@/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";

type TurnOnDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (runImmediately: boolean) => void;
  scheduleTimeLocal: string;
};

export default function TurnOnDialog({
  open,
  onOpenChange,
  onConfirm,
  scheduleTimeLocal,
}: TurnOnDialogProps) {
  const [runImmediately, setRunImmediately] = useState(true);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Turn on daily briefing?</DialogTitle>
          <DialogDescription asChild className="pt-6">
            <div className="flex flex-col gap-6">
              <p>
                Ollie will run every day at {scheduleTimeLocal}, reviewing your
                traces and telling you exactly what to improve.
              </p>
              <p>
                Runs on your Ollie tokens. You can turn it off at any time.{" "}
                <button className="underline underline-offset-4 hover:text-primary">
                  Learn more
                </button>
              </p>
              <div className="flex items-center gap-3">
                <Switch
                  checked={runImmediately}
                  onCheckedChange={setRunImmediately}
                  size="sm"
                />
                <div>
                  <p className="font-medium text-foreground">
                    Run the first report immediately on activation
                  </p>
                  <p className="text-light-slate">
                    You can customize the schedule time and report instructions
                    in settings.
                  </p>
                </div>
              </div>
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={() => onConfirm(runImmediately)}>Turn on</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
