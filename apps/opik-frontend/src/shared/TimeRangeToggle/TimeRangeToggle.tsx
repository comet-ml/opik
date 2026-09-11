import React, { useCallback, useMemo, useState } from "react";
import dayjs from "dayjs";
import { DateRange } from "react-day-picker";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Calendar } from "@/ui/calendar";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  DateRangePreset,
  DateRangeValue,
  getRangePreset,
  PRESET_DATE_RANGES,
} from "@/shared/DateRangeSelect";
import { getDisabledDates } from "@/shared/DateRangeSelect/utils";
import { cn } from "@/lib/utils";

type TimeRangePresetKey = "1D" | "1W" | "1M" | "6M";

type PresetConfig = {
  key: TimeRangePresetKey;
  label: string;
  dateRangePreset?: DateRangePreset;
  getRange?: () => DateRangeValue;
};

const PRESETS: PresetConfig[] = [
  { key: "1D", label: "Last day", dateRangePreset: "past24hours" },
  { key: "1W", label: "Last week", dateRangePreset: "past7days" },
  { key: "1M", label: "Last month", dateRangePreset: "past30days" },
  {
    key: "6M",
    label: "Last 6 months",
    getRange: () => ({
      from: dayjs().subtract(6, "months").startOf("day").toDate(),
      to: dayjs().endOf("day").toDate(),
    }),
  },
];

const RELATIVE_PRESETS = [
  { label: "Last day", dateRangePreset: "past24hours" as DateRangePreset },
  { label: "Last 3 days", dateRangePreset: "past3days" as DateRangePreset },
  { label: "Last week", dateRangePreset: "past7days" as DateRangePreset },
  { label: "Last month", dateRangePreset: "past30days" as DateRangePreset },
  {
    label: "Last quarter",
    getRange: () => ({
      from: dayjs().subtract(3, "months").startOf("day").toDate(),
      to: dayjs().endOf("day").toDate(),
    }),
  },
  {
    label: "Last 6 months",
    getRange: () => ({
      from: dayjs().subtract(6, "months").startOf("day").toDate(),
      to: dayjs().endOf("day").toDate(),
    }),
  },
];

const PRESET_TO_TOGGLE: Partial<Record<DateRangePreset, TimeRangePresetKey>> = {
  past24hours: "1D",
  past7days: "1W",
  past30days: "1M",
};

const getActivePreset = (value: DateRangeValue): TimeRangePresetKey | null => {
  const rangePreset = getRangePreset(value);
  if (rangePreset && rangePreset in PRESET_TO_TOGGLE) {
    return PRESET_TO_TOGGLE[rangePreset]!;
  }

  for (const preset of PRESETS) {
    if (preset.getRange) {
      const range = preset.getRange();
      if (
        dayjs(value.from).isSame(range.from, "day") &&
        dayjs(value.to).isSame(range.to, "day")
      ) {
        return preset.key;
      }
    }
  }
  return null;
};

const getActiveRelativePreset = (value: DateRangeValue): string | null => {
  const rangePreset = getRangePreset(value);
  if (rangePreset) {
    const found = RELATIVE_PRESETS.find(
      (p) => "dateRangePreset" in p && p.dateRangePreset === rangePreset,
    );
    if (found) return found.label;
  }

  for (const preset of RELATIVE_PRESETS) {
    if ("getRange" in preset && preset.getRange) {
      const range = preset.getRange();
      if (
        dayjs(value.from).isSame(range.from, "day") &&
        dayjs(value.to).isSame(range.to, "day")
      ) {
        return preset.label;
      }
    }
  }
  return null;
};

const itemClassName =
  "comet-body-s inline-flex items-center justify-center rounded-sm px-2 h-6 transition-colors hover:bg-muted hover:text-foreground-secondary";
const activeItemClassName = "bg-muted font-medium";

type TimeRangeToggleProps = {
  value: DateRangeValue;
  onChangeValue: (range: DateRangeValue) => void;
  minDate?: Date;
  maxDate?: Date;
};

const TimeRangeToggle: React.FC<TimeRangeToggleProps> = ({
  value,
  onChangeValue,
  minDate,
  maxDate,
}) => {
  const [isCalendarOpen, setIsCalendarOpen] = useState(false);
  const [pendingRange, setPendingRange] = useState<DateRange | undefined>();
  const [pendingPresetLabel, setPendingPresetLabel] = useState<string | null>(
    null,
  );

  const activePreset = useMemo(() => getActivePreset(value), [value]);
  const isCustom = activePreset === null;

  const handlePresetClick = useCallback(
    (preset: PresetConfig) => {
      if (preset.dateRangePreset) {
        onChangeValue(PRESET_DATE_RANGES[preset.dateRangePreset]);
      } else if (preset.getRange) {
        onChangeValue(preset.getRange());
      }
    },
    [onChangeValue],
  );

  const handleCalendarOpen = (open: boolean) => {
    if (open) {
      setPendingRange(value);
      setPendingPresetLabel(getActiveRelativePreset(value));
    }
    setIsCalendarOpen(open);
  };

  const handleRelativePresetClick = (
    preset: (typeof RELATIVE_PRESETS)[number],
  ) => {
    let range: DateRangeValue;
    if ("dateRangePreset" in preset && preset.dateRangePreset) {
      range = PRESET_DATE_RANGES[preset.dateRangePreset];
    } else if ("getRange" in preset && preset.getRange) {
      range = preset.getRange();
    } else {
      return;
    }
    setPendingRange(range);
    setPendingPresetLabel(preset.label);
  };

  const handleDayClick = (day: Date) => {
    setPendingPresetLabel(null);
    setPendingRange((prev) => {
      if (prev?.to) {
        return { from: day, to: undefined };
      }
      if (prev?.from) {
        if (day < prev.from) {
          return { from: day, to: undefined };
        }
        return {
          from: prev.from,
          to: dayjs(day).endOf("day").toDate(),
        };
      }
      return { from: day, to: undefined };
    });
  };

  const handleApply = () => {
    if (pendingRange?.from && pendingRange?.to) {
      onChangeValue({
        from: pendingRange.from,
        to: pendingRange.to,
      });
      setIsCalendarOpen(false);
    }
  };

  const handleReset = () => {
    setPendingRange(value);
    setPendingPresetLabel(getActiveRelativePreset(value));
  };

  const canApply = Boolean(pendingRange?.from && pendingRange?.to);

  const safeMaxDate = useMemo(
    () => (maxDate ? dayjs(maxDate).endOf("day").toDate() : undefined),
    [maxDate],
  );

  const disabledDates = useMemo(
    () => getDisabledDates(minDate, safeMaxDate),
    [minDate, safeMaxDate],
  );

  return (
    <div className="flex items-center gap-1 rounded-md border border-border bg-background p-[3px]">
      {PRESETS.map((preset) => (
        <button
          key={preset.key}
          type="button"
          className={cn(
            itemClassName,
            activePreset === preset.key && activeItemClassName,
          )}
          onClick={() => handlePresetClick(preset)}
        >
          {preset.key}
        </button>
      ))}
      <Popover open={isCalendarOpen} onOpenChange={handleCalendarOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            className={cn(itemClassName, isCustom && activeItemClassName)}
          >
            Custom
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="end">
          <div className="flex">
            <div className="flex w-40 flex-col gap-1 p-4">
              <p className="comet-body-xs mb-2 text-muted-slate">
                Relative time range
              </p>
              {RELATIVE_PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  type="button"
                  className={cn(
                    "comet-body-s rounded-sm px-2 py-1.5 text-left transition-colors hover:bg-muted",
                    pendingPresetLabel === preset.label &&
                      "bg-muted font-medium",
                  )}
                  onClick={() => handleRelativePresetClick(preset)}
                >
                  {preset.label}
                </button>
              ))}
            </div>
            <Separator orientation="vertical" className="h-auto" />
            <div className="flex flex-col p-4">
              <p className="comet-body-xs mb-2 text-muted-slate">
                Absolute time range
              </p>
              <Calendar
                mode="range"
                selected={pendingRange}
                onDayClick={handleDayClick}
                numberOfMonths={1}
                className="rounded-md p-0"
                aria-label="Select date range"
                disabled={disabledDates}
              />
            </div>
          </div>
          <Separator />
          <div className="flex items-center justify-between p-3">
            <Button variant="ghost" size="sm" onClick={handleReset}>
              Reset
            </Button>
            <Button size="sm" onClick={handleApply} disabled={!canApply}>
              Apply
            </Button>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
};

export default TimeRangeToggle;
