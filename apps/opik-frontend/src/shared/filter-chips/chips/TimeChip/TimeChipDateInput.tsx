import React, { useRef, useState } from "react";
import dayjs from "dayjs";
import { Calendar as CalendarIcon } from "lucide-react";
import { Popover, PopoverAnchor, PopoverContent } from "@/ui/popover";
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
  autoFocus?: boolean;
  inputRef: React.RefObject<HTMLInputElement>;
  calendarDefaultTime: SimpleTime;
  onChange: (patch: SlotPatch) => void;
  onPicked?: () => void;
}

const TimeChipDateInput: React.FC<TimeChipDateInputProps> = ({
  slot,
  invalid,
  autoFocus,
  inputRef,
  calendarDefaultTime,
  onChange,
  onPicked,
}) => {
  const [calendarOpen, setCalendarOpen] = useState(false);
  const anchorRef = useRef<HTMLDivElement>(null);
  const ignoreNextFocusRef = useRef(false);
  const justPickedRef = useRef(false);

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

  const handleFocus = () => {
    if (ignoreNextFocusRef.current) {
      ignoreNextFocusRef.current = false;
      return;
    }
    setCalendarOpen(true);
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      ignoreNextFocusRef.current = true;
    }
    flushOnEnter(event);
    const isEditingKey =
      !event.ctrlKey &&
      !event.metaKey &&
      !event.altKey &&
      (event.key.length === 1 ||
        event.key === "Backspace" ||
        event.key === "Delete");
    if (isEditingKey) setCalendarOpen(false);
  };

  const handleIconMouseDown = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
  };

  const handleIconClick = () => {
    inputRef.current?.focus();
    inputRef.current?.select();
    setCalendarOpen(true);
  };

  const handlePick = (next: Date | undefined) => {
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
    justPickedRef.current = true;
    setCalendarOpen(false);
  };

  const handleCloseAutoFocus = (event: Event) => {
    event.preventDefault();
    if (justPickedRef.current) {
      justPickedRef.current = false;
      if (onPicked) {
        onPicked();
      } else {
        ignoreNextFocusRef.current = true;
        inputRef.current?.focus();
      }
    }
  };

  const handleInteractOutside = (event: Event) => {
    const target = event.target as Node | null;
    if (target && anchorRef.current?.contains(target)) {
      event.preventDefault();
    }
  };

  const dateInvalid = slot.dateText.trim() !== "" && !slot.date;
  const showError = (slot.dateTouched && dateInvalid) || invalid;

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <Popover open={calendarOpen} onOpenChange={setCalendarOpen}>
        <PopoverAnchor asChild>
          <div ref={anchorRef} className="relative">
            <DebounceInput
              ref={inputRef}
              dimension="sm"
              type="text"
              autoFocus={autoFocus}
              value={slot.dateText}
              placeholder={dayjs().format(DATE_FORMAT)}
              onValueChange={(next) => commit(String(next ?? ""))}
              onFocus={handleFocus}
              onBlur={() => onChange({ dateTouched: true })}
              onKeyDown={handleKeyDown}
              onPaste={() => setCalendarOpen(false)}
              className={cn("pr-8", showError && "border-destructive")}
            />
            <button
              type="button"
              aria-label="Open calendar"
              aria-haspopup="dialog"
              aria-expanded={calendarOpen}
              onMouseDown={handleIconMouseDown}
              onClick={handleIconClick}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-slate hover:text-foreground"
            >
              <CalendarIcon className="size-[14px]" />
            </button>
          </div>
        </PopoverAnchor>
        <PopoverContent
          align="start"
          sideOffset={6}
          onOpenAutoFocus={(event) => event.preventDefault()}
          onCloseAutoFocus={handleCloseAutoFocus}
          onInteractOutside={handleInteractOutside}
          className="w-auto rounded-md border border-border bg-background p-0 shadow-sm"
        >
          <Calendar
            mode="single"
            selected={slot.date ?? undefined}
            onSelect={handlePick}
          />
        </PopoverContent>
      </Popover>
      {slot.dateTouched && dateInvalid && (
        <FormErrorSkeleton className="comet-body-xs">
          Enter valid date
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default TimeChipDateInput;
