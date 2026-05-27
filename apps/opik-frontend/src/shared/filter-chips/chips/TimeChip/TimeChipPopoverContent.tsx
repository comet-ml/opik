import React, { useState } from "react";
import dayjs from "dayjs";
import { FormErrorSkeleton } from "@/ui/form";
import { PopoverClearFooter } from "@/shared/filter-chips/chips/PopoverClearFooter";
import FilterOperatorSelect, {
  FilterOperatorOption,
} from "@/shared/filter-chips/chips/FilterOperatorSelect";
import { TimeChipMode, TimeChipValue } from "@/shared/filter-chips/types";
import {
  DATE_FORMAT,
  TIME_DEFAULT_MODE,
  combineDateAndTime,
  formatTimeNumeric,
  getPeriod,
  isTimeApplied,
} from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import {
  EMPTY_SLOT,
  type SimpleTime,
  type Slot,
  type SlotPatch,
} from "@/shared/filter-chips/chips/TimeChip/TimeChipPopover.shared";
import TimeChipDateInput from "@/shared/filter-chips/chips/TimeChip/TimeChipDateInput";
import TimeChipTimeInput from "@/shared/filter-chips/chips/TimeChip/TimeChipTimeInput";

interface TimeChipPopoverContentProps {
  fieldLabel: string;
  value: TimeChipValue | undefined;
  onApply: (value: TimeChipValue) => void;
  onClear: () => void;
}

const OPERATOR_OPTIONS: FilterOperatorOption<TimeChipMode>[] = [
  { value: "exactly", label: "Is" },
  { value: "before", label: "Is before" },
  { value: "after", label: "Is after" },
  { value: "between", label: "Is between" },
];

const START_OF_DAY: SimpleTime = { hour: 0, minute: 0 };
const END_OF_DAY: SimpleTime = { hour: 23, minute: 59 };

// "before May 5" excludes May 5; "after May 5" excludes May 5;
// "between A–B" covers A through B inclusive.
const calendarDefaultTime = (
  mode: TimeChipMode,
  position: "A" | "B",
): SimpleTime => {
  if (mode === "after") return END_OF_DAY;
  if (mode === "between" && position === "B") return END_OF_DAY;
  return START_OF_DAY;
};

const slotFromIso = (iso: string | undefined): Slot => {
  if (!iso) return EMPTY_SLOT;
  const d = dayjs(iso);
  if (!d.isValid()) return EMPTY_SLOT;
  return {
    date: d.startOf("day").toDate(),
    dateText: d.format(DATE_FORMAT),
    dateTouched: false,
    time: { hour: d.hour(), minute: d.minute() },
    timeText: formatTimeNumeric(d.hour(), d.minute()),
    timeTouched: false,
    period: getPeriod(d.hour()),
  };
};

const buildValue = (
  mode: TimeChipMode,
  isoA: string | null,
  isoB: string | null,
): TimeChipValue | null => {
  if (!isoA) return null;
  switch (mode) {
    case "exactly":
      return { mode, at: isoA };
    case "before":
      return { mode, before: isoA };
    case "after":
      return { mode, after: isoA };
    case "between":
      if (!isoB || dayjs(isoB).isBefore(dayjs(isoA))) return null;
      return { mode, start: isoA, end: isoB };
  }
};

const TimeChipPopoverContent: React.FC<TimeChipPopoverContentProps> = ({
  fieldLabel,
  value,
  onApply,
  onClear,
}) => {
  const [mode, setMode] = useState<TimeChipMode>(
    value?.mode ?? TIME_DEFAULT_MODE,
  );

  const [slotA, setSlotA] = useState<Slot>(() => {
    if (value?.mode === "exactly") return slotFromIso(value.at);
    if (value?.mode === "between") return slotFromIso(value.start);
    if (value?.mode === "before") return slotFromIso(value.before);
    if (value?.mode === "after") return slotFromIso(value.after);
    return EMPTY_SLOT;
  });
  const [slotB, setSlotB] = useState<Slot>(() =>
    value?.mode === "between" ? slotFromIso(value.end) : EMPTY_SLOT,
  );

  const applyFromSlots = (nextMode: TimeChipMode, a: Slot, b: Slot): void => {
    const next = buildValue(
      nextMode,
      combineDateAndTime(a.date, a.time),
      combineDateAndTime(b.date, b.time),
    );
    if (next) onApply(next);
    else onClear();
  };

  const isRangeInverted = (() => {
    if (mode !== "between") return false;
    const isoA = combineDateAndTime(slotA.date, slotA.time);
    const isoB = combineDateAndTime(slotB.date, slotB.time);
    return Boolean(isoA && isoB && dayjs(isoB).isBefore(dayjs(isoA)));
  })();

  const semanticChanged = (a: Slot, b: Slot): boolean =>
    a.date !== b.date || a.time !== b.time;

  const patchSlotA = (patch: SlotPatch) => {
    const next = { ...slotA, ...patch };
    setSlotA(next);
    if (semanticChanged(slotA, next)) applyFromSlots(mode, next, slotB);
  };

  const patchSlotB = (patch: SlotPatch) => {
    const next = { ...slotB, ...patch };
    setSlotB(next);
    if (semanticChanged(slotB, next)) applyFromSlots(mode, slotA, next);
  };

  const handleModeChange = (nextMode: TimeChipMode) => {
    setMode(nextMode);
    applyFromSlots(nextMode, slotA, slotB);
  };

  const handleClear = () => {
    setMode(TIME_DEFAULT_MODE);
    setSlotA(EMPTY_SLOT);
    setSlotB(EMPTY_SLOT);
    onClear();
  };

  const draftHint = (() => {
    const incomplete = (s: Slot) => Boolean(s.date) !== Boolean(s.time);
    if (incomplete(slotA) || (mode === "between" && incomplete(slotB))) {
      return "Enter a time to apply";
    }
    if (mode === "between") {
      const aReady = Boolean(slotA.date && slotA.time);
      const bReady = Boolean(slotB.date && slotB.time);
      if (aReady !== bReady) return "Complete the range to apply";
    }
    return null;
  })();

  return (
    <div className="flex w-[320px] flex-col gap-4 p-3">
      <FilterOperatorSelect
        fieldLabel={fieldLabel}
        value={mode}
        options={OPERATOR_OPTIONS}
        onChange={handleModeChange}
      />

      {mode === "between" ? (
        <>
          <SlotRow
            label="Start"
            slot={slotA}
            calendarDefaultTime={calendarDefaultTime(mode, "A")}
            onChange={patchSlotA}
          />
          <div className="flex flex-col gap-1">
            <SlotRow
              label="End"
              slot={slotB}
              invalid={isRangeInverted}
              calendarDefaultTime={calendarDefaultTime(mode, "B")}
              onChange={patchSlotB}
            />
            {isRangeInverted && (
              <FormErrorSkeleton className="comet-body-xs pl-[39px]">
                End must be after start
              </FormErrorSkeleton>
            )}
          </div>
        </>
      ) : (
        <SlotRow
          slot={slotA}
          calendarDefaultTime={calendarDefaultTime(mode, "A")}
          onChange={patchSlotA}
        />
      )}

      {draftHint && (
        <p className="comet-body-xs text-muted-slate">{draftHint}</p>
      )}

      <PopoverClearFooter
        onClear={handleClear}
        disabled={!isTimeApplied(value)}
      />
    </div>
  );
};

interface SlotRowProps {
  label?: string;
  slot: Slot;
  invalid?: boolean;
  calendarDefaultTime: SimpleTime;
  onChange: (patch: SlotPatch) => void;
}

const SlotRow: React.FC<SlotRowProps> = ({
  label,
  slot,
  invalid,
  calendarDefaultTime,
  onChange,
}) => (
  <div className="flex w-full items-start gap-2">
    {label && (
      <span className="comet-body-xs mt-2 w-[31px] shrink-0 text-muted-slate">
        {label}
      </span>
    )}
    <div className="flex min-w-0 flex-1 items-start gap-1">
      <TimeChipDateInput
        slot={slot}
        invalid={invalid}
        calendarDefaultTime={calendarDefaultTime}
        onChange={onChange}
      />
      <TimeChipTimeInput slot={slot} invalid={invalid} onChange={onChange} />
    </div>
  </div>
);

export default TimeChipPopoverContent;
