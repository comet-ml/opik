import { useCallback, useMemo, useState } from "react";
import dayjs from "dayjs";
import { formatLocalTimeAsUtc, parseUtcTimeToLocalDate } from "@/lib/date";
import { Textarea } from "@/ui/textarea";
import TimePicker from "@/shared/TimePicker/TimePicker";

export function useReportSettings(
  initialScheduleTime: string,
  initialCustomPrompt: string,
) {
  const [timeDate, setTimeDate] = useState<Date | undefined>(() =>
    parseUtcTimeToLocalDate(initialScheduleTime),
  );
  const [customPrompt, setCustomPrompt] = useState(initialCustomPrompt);

  const scheduleTimeLocal = useMemo(
    () => (timeDate ? dayjs(timeDate).format("h:mm A") : ""),
    [timeDate],
  );

  const getUtcTime = () =>
    timeDate
      ? formatLocalTimeAsUtc(dayjs(timeDate).format("HH:mm:ss"))
      : initialScheduleTime;

  const reset = useCallback((scheduleTime: string, prompt: string) => {
    setTimeDate(parseUtcTimeToLocalDate(scheduleTime));
    setCustomPrompt(prompt);
  }, []);

  return {
    timeDate,
    setTimeDate,
    customPrompt,
    setCustomPrompt,
    scheduleTimeLocal,
    getUtcTime,
    reset,
  };
}

export default function ReportSettingsFields({
  timeDate,
  setTimeDate,
  customPrompt,
  setCustomPrompt,
  scheduleTimeLocal,
}: {
  timeDate: Date | undefined;
  setTimeDate: (date: Date | undefined) => void;
  customPrompt: string;
  setCustomPrompt: (value: string) => void;
  scheduleTimeLocal: string;
}) {
  return (
    <>
      <div className="flex flex-col gap-2">
        <p className="font-medium text-foreground">Schedule time</p>
        <p className="text-light-slate">
          Reports run daily at {scheduleTimeLocal} (your local time).
        </p>
        <TimePicker date={timeDate} setDate={setTimeDate} />
      </div>

      <div className="flex flex-col gap-2">
        <p className="font-medium text-foreground">Custom instructions</p>
        <p className="text-light-slate">
          Add extra instructions to tailor the report to your needs.
        </p>
        <Textarea
          value={customPrompt}
          onChange={(e) => setCustomPrompt(e.target.value)}
          placeholder='e.g. "Focus on latency regressions and cost anomalies"'
          rows={4}
          className="min-h-0"
        />
      </div>
    </>
  );
}
