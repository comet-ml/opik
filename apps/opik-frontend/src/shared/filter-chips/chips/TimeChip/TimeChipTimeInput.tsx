import React from "react";
import { ChevronsUpDown, Clock } from "lucide-react";
import { FormErrorSkeleton } from "@/ui/form";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { cn } from "@/lib/utils";
import type { Period } from "@/ui/time-picker-utils";
import {
  formatTimeNumeric,
  getPeriod,
  parseTimeInput,
  shiftHourForPeriod,
} from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import {
  flushOnEnter,
  type Slot,
  type SlotPatch,
} from "@/shared/filter-chips/chips/TimeChip/TimeChipPopover.shared";

interface TimeChipTimeInputProps {
  slot: Slot;
  invalid?: boolean;
  inputRef?: React.Ref<HTMLInputElement>;
  onChange: (patch: SlotPatch) => void;
}

const TimeChipTimeInput: React.FC<TimeChipTimeInputProps> = ({
  slot,
  invalid,
  inputRef,
  onChange,
}) => {
  const commit = (raw: string) => {
    if (raw.trim() === "") {
      onChange({ time: null, timeText: "" });
      return;
    }
    const parsed = parseTimeInput(raw, slot.period);
    if (parsed) {
      onChange({
        time: parsed,
        timeText: formatTimeNumeric(parsed.hour, parsed.minute),
        period: getPeriod(parsed.hour),
      });
    } else {
      onChange({ time: null, timeText: raw });
    }
  };

  const togglePeriod = () => {
    const nextPeriod: Period = slot.period === "AM" ? "PM" : "AM";
    if (slot.time) {
      onChange({
        period: nextPeriod,
        time: {
          hour: shiftHourForPeriod(slot.time.hour, nextPeriod),
          minute: slot.time.minute,
        },
      });
    } else {
      onChange({ period: nextPeriod });
    }
  };

  const timeInvalid = slot.timeText.trim() !== "" && !slot.time;
  const showError = (slot.timeTouched && timeInvalid) || invalid;

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <div
        className={cn(
          "group flex h-8 items-center gap-1 rounded-md border bg-background pl-3 pr-3 hover:shadow-sm",
          showError
            ? "border-destructive"
            : "border-border focus-within:border-primary",
        )}
      >
        <DebounceInput
          ref={inputRef}
          variant="unstyled"
          dimension="none"
          type="text"
          value={slot.timeText}
          placeholder="12:00"
          onValueChange={(next) => commit(String(next ?? ""))}
          onBlur={() => onChange({ timeTouched: true })}
          onKeyDown={flushOnEnter}
          className="comet-body-s peer min-w-0 flex-1 bg-transparent text-foreground outline-none placeholder:text-light-slate focus-visible:outline-none"
        />
        <button
          type="button"
          onMouseDown={(event) => event.preventDefault()}
          onClick={togglePeriod}
          className="comet-body-s flex shrink-0 cursor-pointer items-center gap-0.5 text-foreground"
          aria-label={`Toggle AM/PM (current: ${slot.period})`}
        >
          <span>{slot.period}</span>
          <ChevronsUpDown className="hidden size-[14px] text-muted-slate peer-focus:inline-block" />
        </button>
        <Clock className="pointer-events-none size-[14px] shrink-0 text-muted-slate" />
      </div>
      {slot.timeTouched && timeInvalid && (
        <FormErrorSkeleton className="comet-body-xs">
          Enter valid time
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default TimeChipTimeInput;
