import React, { useState } from "react";
import dayjs from "dayjs";
import { Calendar as CalendarIcon, ChevronsUpDown, Clock } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Calendar } from "@/ui/calendar";
import { Input } from "@/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { cn } from "@/lib/utils";
import type { Period } from "@/ui/time-picker-utils";
import { PopoverClearFooter } from "@/shared/filter-chips/chips/PopoverClearFooter";
import { TimeChipMode, TimeChipValue } from "@/shared/filter-chips/types";
import {
  DATE_FORMAT,
  combineDateAndTime,
  formatTimeNumeric,
  getPeriod,
  parseDateInput,
  parseTimeInput,
  shiftHourForPeriod,
} from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";

interface TimeChipPopoverContentProps {
  value: TimeChipValue | undefined;
  onApply: (value: TimeChipValue) => void;
  onClear: () => void;
}

const MODES: { mode: TimeChipMode; label: string }[] = [
  { mode: "exactly", label: "Exactly" },
  { mode: "between", label: "Between" },
  { mode: "before", label: "Before" },
  { mode: "after", label: "After" },
];

interface Slot {
  date: Date | null;
  dateText: string;
  time: { hour: number; minute: number } | null;
  timeText: string;
  period: Period;
  timeInvalid: boolean;
}

const today = (): Date => dayjs().startOf("day").toDate();

const emptySlot = (
  initial: { date?: Date; time?: { hour: number; minute: number } } = {},
): Slot => {
  const date = initial.date ?? today();
  const time = initial.time ?? { hour: 0, minute: 0 };
  return {
    date,
    dateText: dayjs(date).format(DATE_FORMAT),
    time,
    timeText: formatTimeNumeric(time.hour, time.minute),
    period: getPeriod(time.hour),
    timeInvalid: false,
  };
};

const slotFromIso = (iso: string | undefined): Slot => {
  if (!iso) return emptySlot();
  const d = dayjs(iso);
  if (!d.isValid()) return emptySlot();
  return emptySlot({
    date: d.startOf("day").toDate(),
    time: { hour: d.hour(), minute: d.minute() },
  });
};

const slotToIso = (slot: Slot): string | null =>
  combineDateAndTime(slot.date, slot.time);

const TimeChipPopoverContent: React.FC<TimeChipPopoverContentProps> = ({
  value,
  onApply,
  onClear,
}) => {
  const [mode, setMode] = useState<TimeChipMode>(value?.mode ?? "exactly");

  const [slotA, setSlotA] = useState<Slot>(() => {
    if (value?.mode === "exactly") return slotFromIso(value.at);
    if (value?.mode === "between") return slotFromIso(value.start);
    if (value?.mode === "before") return slotFromIso(value.before);
    if (value?.mode === "after") return slotFromIso(value.after);
    return emptySlot();
  });
  const [slotB, setSlotB] = useState<Slot>(() => {
    if (value?.mode === "between") return slotFromIso(value.end);
    return emptySlot();
  });

  const isRangeInverted = (a: Slot, b: Slot): boolean => {
    const isoA = slotToIso(a);
    const isoB = slotToIso(b);
    if (!isoA || !isoB) return false;
    return dayjs(isoB).isBefore(dayjs(isoA));
  };

  const applyFromSlots = (nextMode: TimeChipMode, a: Slot, b: Slot): void => {
    const isoA = slotToIso(a);
    switch (nextMode) {
      case "exactly":
        if (isoA) onApply({ mode: "exactly", at: isoA });
        else onClear();
        break;
      case "before":
        if (isoA) onApply({ mode: "before", before: isoA });
        else onClear();
        break;
      case "after":
        if (isoA) onApply({ mode: "after", after: isoA });
        else onClear();
        break;
      case "between": {
        const isoB = slotToIso(b);
        if (isoA && isoB && !isRangeInverted(a, b)) {
          onApply({ mode: "between", start: isoA, end: isoB });
        } else if (!isoA && !isoB) {
          onClear();
        }
        break;
      }
    }
  };

  const rangeInvalid = mode === "between" && isRangeInverted(slotA, slotB);

  const handleModeChange = (nextMode: TimeChipMode) => {
    setMode(nextMode);
    applyFromSlots(nextMode, slotA, slotB);
  };

  return (
    <div className="flex w-[320px] flex-col gap-4 p-3">
      <ToggleGroup
        type="single"
        variant="filter"
        size="xs"
        value={mode}
        onValueChange={(next) => {
          if (next) handleModeChange(next as TimeChipMode);
        }}
        className="w-full"
      >
        {MODES.map((m) => (
          <ToggleGroupItem key={m.mode} value={m.mode} className="flex-1">
            {m.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      {mode === "between" ? (
        <>
          <SlotRow
            label="Start"
            slot={slotA}
            onChange={(next) => {
              setSlotA(next);
              applyFromSlots(mode, next, slotB);
            }}
          />
          <div className="flex flex-col gap-1">
            <SlotRow
              label="End"
              slot={slotB}
              invalid={rangeInvalid}
              onChange={(next) => {
                setSlotB(next);
                applyFromSlots(mode, slotA, next);
              }}
            />
            {rangeInvalid && (
              <p className="comet-body-xs pl-[39px] text-warning">
                End must be after start
              </p>
            )}
          </div>
        </>
      ) : (
        <SlotRow
          slot={slotA}
          onChange={(next) => {
            setSlotA(next);
            applyFromSlots(mode, next, slotB);
          }}
        />
      )}

      <PopoverClearFooter onClear={onClear} />
    </div>
  );
};

interface SlotRowProps {
  label?: string;
  slot: Slot;
  invalid?: boolean;
  onChange: (slot: Slot) => void;
}

const SlotRow: React.FC<SlotRowProps> = ({
  label,
  slot,
  invalid,
  onChange,
}) => {
  return (
    <div className="flex w-full items-start gap-2">
      {label && (
        <span className="comet-body-xs mt-2 w-[31px] shrink-0 text-muted-slate">
          {label}
        </span>
      )}
      <div className="flex min-w-0 flex-1 items-start gap-1">
        <DateInput slot={slot} invalid={invalid} onChange={onChange} />
        <TimeInput slot={slot} invalid={invalid} onChange={onChange} />
      </div>
    </div>
  );
};

const DateInput: React.FC<SlotRowProps> = ({ slot, invalid, onChange }) => {
  const [calendarOpen, setCalendarOpen] = useState(false);

  const commitDateText = (raw: string) => {
    const parsed = parseDateInput(raw);
    if (parsed) {
      onChange({
        ...slot,
        date: parsed,
        dateText: dayjs(parsed).format(DATE_FORMAT),
      });
    } else if (raw.trim() === "") {
      onChange({ ...slot, date: null, dateText: "" });
    } else {
      onChange({ ...slot, dateText: raw });
    }
  };

  return (
    <div className="relative min-w-0 flex-1">
      <Input
        dimension="sm"
        type="text"
        value={slot.dateText}
        placeholder="yyyy/mm/dd"
        onChange={(event) =>
          onChange({ ...slot, dateText: event.target.value })
        }
        onBlur={(event) => commitDateText(event.target.value)}
        className={cn("pr-8", invalid && "border-warning")}
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
            onSelect={(next) => {
              if (next) {
                onChange({
                  ...slot,
                  date: next,
                  dateText: dayjs(next).format(DATE_FORMAT),
                });
                setCalendarOpen(false);
              }
            }}
          />
        </PopoverContent>
      </Popover>
    </div>
  );
};

const TimeInput: React.FC<SlotRowProps> = ({ slot, invalid, onChange }) => {
  const [focused, setFocused] = useState(false);

  const commitTimeText = (raw: string) => {
    if (raw.trim() === "") {
      onChange({ ...slot, time: null, timeText: "", timeInvalid: false });
      return;
    }
    const parsed = parseTimeInput(raw, slot.period);
    if (parsed) {
      onChange({
        ...slot,
        time: parsed,
        timeText: formatTimeNumeric(parsed.hour, parsed.minute),
        period: getPeriod(parsed.hour),
        timeInvalid: false,
      });
    } else {
      onChange({ ...slot, timeText: raw, timeInvalid: true });
    }
  };

  const togglePeriod = () => {
    const nextPeriod: Period = slot.period === "AM" ? "PM" : "AM";
    if (slot.time) {
      const nextHour = shiftHourForPeriod(slot.time.hour, nextPeriod);
      onChange({
        ...slot,
        period: nextPeriod,
        time: { hour: nextHour, minute: slot.time.minute },
      });
    } else {
      onChange({ ...slot, period: nextPeriod });
    }
  };

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <div
        className={cn(
          "flex h-8 items-center gap-1 rounded-md border bg-background pl-3 pr-3",
          slot.timeInvalid || invalid
            ? "border-warning"
            : focused
              ? "border-primary"
              : "border-border",
        )}
      >
        <input
          type="text"
          value={slot.timeText}
          placeholder="hh:mm"
          onFocus={() => setFocused(true)}
          onBlur={(event) => {
            setFocused(false);
            commitTimeText(event.target.value);
          }}
          onChange={(event) =>
            onChange({
              ...slot,
              timeText: event.target.value,
              timeInvalid: false,
            })
          }
          className="comet-body-s min-w-0 flex-1 bg-transparent text-foreground outline-none placeholder:text-light-slate"
        />
        <button
          type="button"
          onMouseDown={(event) => event.preventDefault()}
          onClick={togglePeriod}
          className="comet-body-s flex shrink-0 cursor-pointer items-center gap-0.5 text-foreground"
          aria-label={`Toggle AM/PM (current: ${slot.period})`}
        >
          <span>{slot.period}</span>
          {focused && (
            <ChevronsUpDown className="size-[14px] text-muted-slate" />
          )}
        </button>
        <Clock className="pointer-events-none size-[14px] shrink-0 text-muted-slate" />
      </div>
      {slot.timeInvalid && (
        <p className="comet-body-xs px-0.5 text-warning">Enter valid time</p>
      )}
    </div>
  );
};

export default TimeChipPopoverContent;
