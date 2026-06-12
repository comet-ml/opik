import { useEffect, useState } from "react";
import dayjs from "dayjs";
import usePluginsStore from "@/store/PluginsStore";
import { formatLocalTimeAsUtc, parseUtcTimeToLocalDate } from "@/lib/date";
import { Button } from "@/ui/button";
import { Textarea } from "@/ui/textarea";
import { Switch } from "@/ui/switch";
import { Separator } from "@/ui/separator";
import TimeInput from "@/shared/TimeInput/TimeInput";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { ReportPreferenceSettings } from "@/types/ollie-reports";

type SettingsDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  enabled: boolean;
  scheduleTime: string;
  customPrompt: string;
  onSave: (settings: ReportPreferenceSettings) => void;
};

export default function SettingsDialog({
  open,
  onOpenChange,
  enabled,
  scheduleTime,
  customPrompt,
  onSave,
}: SettingsDialogProps) {
  const [localEnabled, setLocalEnabled] = useState(enabled);
  const [timeDate, setTimeDate] = useState<Date | undefined>(() =>
    parseUtcTimeToLocalDate(scheduleTime),
  );
  const [localCustomPrompt, setLocalCustomPrompt] = useState(customPrompt);
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  useEffect(() => {
    if (open) {
      setLocalEnabled(enabled);
      setTimeDate(parseUtcTimeToLocalDate(scheduleTime));
      setLocalCustomPrompt(customPrompt);
    }
  }, [open, enabled, scheduleTime, customPrompt]);

  const handleSave = () => {
    const utcTime = timeDate
      ? formatLocalTimeAsUtc(dayjs(timeDate).format("HH:mm:ss"))
      : scheduleTime;
    onSave({
      enabled: localEnabled,
      scheduleTime: utcTime,
      customPrompt: localCustomPrompt,
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[580px]">
        <DialogHeader>
          <DialogTitle>Daily briefing settings</DialogTitle>
          <DialogDescription asChild className="pt-4">
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-3">
                <Switch
                  checked={localEnabled}
                  onCheckedChange={setLocalEnabled}
                  size="sm"
                />
                <div>
                  {localEnabled ? (
                    <>
                      <p className="font-medium text-foreground">
                        Daily briefing active
                      </p>
                      <p className="text-light-slate">
                        Turn off to pause automated reports. You can reactivate
                        at any time.
                      </p>
                    </>
                  ) : (
                    <>
                      <p className="font-medium text-foreground">
                        Daily briefing is paused
                      </p>
                      <p className="text-light-slate">
                        Turn on to resume automated reports.
                      </p>
                    </>
                  )}
                </div>
              </div>

              <Separator />

              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1">
                  <p className="font-medium text-foreground">Scheduled for</p>
                  <TimeInput
                    date={timeDate}
                    setDate={setTimeDate}
                    disabled={!localEnabled}
                  />
                </div>

                <div className="flex flex-col gap-1">
                  <p className="font-medium text-foreground">
                    Custom instructions
                  </p>
                  <Textarea
                    value={localCustomPrompt}
                    onChange={(e) => setLocalCustomPrompt(e.target.value)}
                    placeholder="Add custom instructions to tailor the report"
                    rows={4}
                    maxLength={5000}
                    className="min-h-0"
                    disabled={!localEnabled}
                  />
                </div>

                <div className="flex flex-col gap-1 py-2">
                  <p className="font-medium text-foreground">Billing</p>
                  <p className="text-foreground">
                    Runs on your Ollie tokens. {BillingLink && <BillingLink />}
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
          <Button onClick={handleSave}>Save changes</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
