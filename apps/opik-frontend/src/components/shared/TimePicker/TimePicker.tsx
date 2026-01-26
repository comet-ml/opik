import React, { useEffect, useRef, useState } from "react";
import { Label } from "@/components/ui/label";
import { TimePickerInput } from "@/components/ui/time-picker-input";
import { TimePeriodSelect } from "@/components/ui/time-picker-period-select";
import { getPeriodByDate, Period } from "@/components/ui/time-picker-utils";

type TimePickerDemoProps = {
  date: Date | undefined;
  setDate: (date: Date | undefined) => void;
  is12HourFormat?: boolean;
  includeSeconds?: boolean;
};

const TimePicker: React.FC<TimePickerDemoProps> = ({
  date,
  setDate,
  is12HourFormat = true,
  includeSeconds = false,
}) => {
  const [period, setPeriod] = useState<Period>(getPeriodByDate(date));

  const minuteRef = useRef<HTMLInputElement>(null);
  const hourRef = useRef<HTMLInputElement>(null);
  const secondRef = useRef<HTMLInputElement>(null);
  const periodRef = useRef<HTMLButtonElement>(null);

  // sync period state with date changes
  useEffect(() => {
    if (date) {
      setPeriod(getPeriodByDate(date));
    }
  }, [date]);

  return (
    <div className="flex items-end gap-2">
      <div className="grid gap-1 text-center">
        <Label htmlFor="hours" className="text-xs">
          Hours
        </Label>
        <TimePickerInput
          picker={is12HourFormat ? "12hours" : "hours"}
          period={is12HourFormat ? period : undefined}
          date={date}
          setDate={setDate}
          ref={hourRef}
          onRightFocus={() => minuteRef.current?.focus()}
        />
      </div>
      <div className="grid gap-1 text-center">
        <Label htmlFor="minutes" className="text-xs">
          Minutes
        </Label>
        <TimePickerInput
          picker="minutes"
          id="minutes12"
          date={date}
          setDate={setDate}
          ref={minuteRef}
          onLeftFocus={() => hourRef.current?.focus()}
          onRightFocus={() => {
            if (includeSeconds) {
              secondRef.current?.focus();
            } else if (is12HourFormat) {
              periodRef.current?.focus();
            }
          }}
        />
      </div>
      {includeSeconds && (
        <div className="grid gap-1 text-center">
          <Label htmlFor="seconds" className="text-xs">
            Seconds
          </Label>
          <TimePickerInput
            picker="seconds"
            id="seconds12"
            date={date}
            setDate={setDate}
            ref={secondRef}
            onLeftFocus={() => minuteRef.current?.focus()}
            onRightFocus={() => {
              if (is12HourFormat) {
                periodRef.current?.focus();
              }
            }}
          />
        </div>
      )}
      {is12HourFormat && (
        <div className="grid gap-1 text-center">
          <Label htmlFor="period" className="text-xs">
            Period
          </Label>
          <TimePeriodSelect
            period={period}
            setPeriod={setPeriod}
            date={date}
            setDate={setDate}
            ref={periodRef}
            onLeftFocus={() => {
              if (includeSeconds) {
                secondRef.current?.focus();
              } else {
                minuteRef.current?.focus();
              }
            }}
          />
        </div>
      )}
    </div>
  );
};

export default TimePicker;
