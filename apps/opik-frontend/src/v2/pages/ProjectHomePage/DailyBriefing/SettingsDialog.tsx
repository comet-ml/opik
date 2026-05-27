import { useEffect, useState } from "react";
import dayjs from "dayjs";
import usePluginsStore from "@/store/PluginsStore";
import { formatLocalTimeAsUtc, parseUtcTimeToLocalDate } from "@/lib/date";
import { Button } from "@/ui/button";
import { Textarea } from "@/ui/textarea";
import { Switch } from "@/ui/switch";
import TimePicker from "@/shared/TimePicker/TimePicker";
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
    setLocalEnabled(enabled);
    setTimeDate(parseUtcTimeToLocalDate(scheduleTime));
    setLocalCustomPrompt(customPrompt);
  }, [enabled, scheduleTime, customPrompt]);

  const scheduleTimeLocal = timeDate ? dayjs(timeDate).format("h:mm A") : "";

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
          <DialogDescription asChild className="pt-6">
            <div className="flex flex-col gap-6">
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

              <div className="flex flex-col gap-2">
                <p className="font-medium text-foreground">Schedule time</p>
                <p className="text-light-slate">
                  Reports run daily at {scheduleTimeLocal} (your local time).
                </p>
                <TimePicker date={timeDate} setDate={setTimeDate} />
              </div>

              <div className="flex flex-col gap-2">
                <p className="font-medium text-foreground">
                  Custom instructions
                </p>
                <p className="text-light-slate">
                  Add extra instructions to tailor the report to your needs.
                </p>
                <Textarea
                  value={localCustomPrompt}
                  onChange={(e) => setLocalCustomPrompt(e.target.value)}
                  placeholder='e.g. "Focus on latency regressions and cost anomalies"'
                  rows={4}
                  className="min-h-0"
                />
              </div>

              <p className="text-foreground">
                <span className="font-medium">Billing:</span> Runs on your Ollie
                tokens. {BillingLink && <BillingLink />}
              </p>
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
