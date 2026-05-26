import React, { useState } from "react";
import dayjs from "dayjs";
import { Calendar as CalendarIcon } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Calendar } from "@/ui/calendar";
import { FormErrorSkeleton } from "@/ui/form";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { cn } from "@/lib/utils";
import {
  DATE_FORMAT,
  formatTimeNumeric,
  getPeriod,
  parseDateInput,
} from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import {
  flushOnEnter,
  type SimpleTime,
  type Slot,
  type SlotPatch,
} from "@/shared/filter-chips/chips/TimeChip/TimeChipPopover.shared";

interface TimeChipDateInputProps {
  slot: Slot;
  invalid?: boolean;
  calendarDefaultTime: SimpleTime;
  onChange: (patch: SlotPatch) => void;
}

const TimeChipDateInput: React.FC<TimeChipDateInputProps> = ({
  slot,
  invalid,
  calendarDefaultTime,
  onChange,
}) => {
  const [calendarOpen, setCalendarOpen] = useState(false);

  const commit = (raw: string) => {
    const parsed = parseDateInput(raw);
    if (parsed) {
      onChange({ date: parsed, dateText: dayjs(parsed).format(DATE_FORMAT) });
    } else if (raw.trim() === "") {
      onChange({ date: null, dateText: "" });
    } else {
      onChange({ date: null, dateText: raw });
    }
  };

  const pickCalendarDate = (next: Date | undefined) => {
    if (!next) {
      onChange({ date: null, dateText: "" });
    } else if (slot.time) {
      onChange({ date: next, dateText: dayjs(next).format(DATE_FORMAT) });
    } else {
      onChange({
        date: next,
        dateText: dayjs(next).format(DATE_FORMAT),
        time: calendarDefaultTime,
        timeText: formatTimeNumeric(
          calendarDefaultTime.hour,
          calendarDefaultTime.minute,
        ),
        period: getPeriod(calendarDefaultTime.hour),
      });
    }
    setCalendarOpen(false);
  };

  const dateInvalid = slot.dateText.trim() !== "" && !slot.date;
  const showError = (slot.dateTouched && dateInvalid) || invalid;

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <div className="relative">
        <DebounceInput
          dimension="sm"
          type="text"
          value={slot.dateText}
          placeholder={dayjs().format(DATE_FORMAT)}
          onValueChange={(next) => commit(String(next ?? ""))}
          onBlur={() => onChange({ dateTouched: true })}
          onKeyDown={flushOnEnter}
          className={cn("pr-8", showError && "border-destructive")}
        />
        <Popover open={calendarOpen} onOpenChange={setCalendarOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label="Open calendar"
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-slate hover:text-foreground"
            >
              <CalendarIcon className="size-[14px]" />
            </button>
          </PopoverTrigger>
          <PopoverContent
            align="start"
            sideOffset={6}
            className="w-auto rounded-md border border-border bg-background p-0 shadow-sm"
          >
            <Calendar
              mode="single"
              selected={slot.date ?? undefined}
              onSelect={pickCalendarDate}
            />
          </PopoverContent>
        </Popover>
      </div>
      {slot.dateTouched && dateInvalid && (
        <FormErrorSkeleton className="comet-body-xs">
          Enter valid date
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default TimeChipDateInput;
