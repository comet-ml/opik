import React, { useEffect, useState } from "react";
import { ChevronsUpDown, Clock } from "lucide-react";
import dayjs from "dayjs";
import { cn } from "@/lib/utils";
import type { Period } from "@/ui/time-picker-utils";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import {
  formatTimeNumeric,
  getPeriod,
  parseTimeInput,
  shiftHourForPeriod,
} from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";

interface TimeInputProps {
  date: Date | undefined;
  setDate: (date: Date | undefined) => void;
  disabled?: boolean;
  placeholder?: string;
}

const TimeInput: React.FC<TimeInputProps> = ({
  date,
  setDate,
  disabled,
  placeholder = dayjs().format("h:mm"),
}) => {
  const [timeText, setTimeText] = useState("");
  const [period, setPeriod] = useState<Period>("AM");

  useEffect(() => {
    if (date) {
      setTimeText(formatTimeNumeric(date.getHours(), date.getMinutes()));
      setPeriod(getPeriod(date.getHours()));
    }
  }, [date]);

  const updateTime = (hour: number, minute: number) => {
    const base = date ? dayjs(date) : dayjs().startOf("day");
    setDate(base.hour(hour).minute(minute).second(0).millisecond(0).toDate());
  };

  const handleInput = (raw: string) => {
    if (raw.trim() === "") {
      setTimeText("");
      return;
    }
    const parsed = parseTimeInput(raw, period);
    if (parsed) {
      setTimeText(formatTimeNumeric(parsed.hour, parsed.minute));
      setPeriod(getPeriod(parsed.hour));
      updateTime(parsed.hour, parsed.minute);
    }
  };

  const handlePeriodToggle = () => {
    const next: Period = period === "AM" ? "PM" : "AM";
    setPeriod(next);
    if (date) {
      updateTime(shiftHourForPeriod(date.getHours(), next), date.getMinutes());
    }
  };

  return (
    <div
      className={cn(
        "group flex h-8 w-32 items-center gap-1 rounded-md border bg-background pl-3 pr-3 hover:shadow-sm",
        disabled && "pointer-events-none opacity-50",
        "border-border focus-within:border-primary",
      )}
    >
      <DebounceInput
        variant="unstyled"
        dimension="none"
        type="text"
        value={timeText}
        placeholder={placeholder}
        onValueChange={(next) => handleInput(String(next ?? ""))}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.currentTarget.blur();
            e.currentTarget.focus();
          }
        }}
        disabled={disabled}
        className="comet-body-s peer min-w-0 flex-1 bg-transparent text-foreground outline-none placeholder:text-light-slate focus-visible:outline-none"
      />
      <button
        type="button"
        onMouseDown={(e) => e.preventDefault()}
        onClick={handlePeriodToggle}
        disabled={disabled}
        className="comet-body-s flex shrink-0 cursor-pointer items-center gap-0.5 text-foreground"
        aria-label={`Toggle AM/PM (current: ${period})`}
      >
        <span className="w-6 text-center">{period}</span>
        <ChevronsUpDown className="hidden size-[14px] text-muted-slate peer-focus:inline-block" />
      </button>
      <Clock className="pointer-events-none size-[14px] shrink-0 text-muted-slate" />
    </div>
  );
};

export default TimeInput;
