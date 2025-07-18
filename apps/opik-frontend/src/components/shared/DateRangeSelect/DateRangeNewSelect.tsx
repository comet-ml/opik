import React, { createContext, useContext } from "react";
import { Calendar } from "@/components/ui/calendar";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
} from "@/components/ui/select";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import dayjs from "dayjs";
import { DateRange } from "react-day-picker";
import { CalendarIcon, Check } from "lucide-react";
import { Separator } from "@/components/ui/separator";

export type DateRangePreset = "today" | "last3days" | "lastWeek" | "lastMonth";

// Range is always valid dates
export type DateRangeValue = {
  from: Date;
  to: Date;
};

export const DEFAULT_DATE_RANGE: DateRangeValue = {
  from: dayjs().startOf("day").toDate(),
  to: dayjs().endOf("day").toDate(),
};

// Helper function to create disabled date function
const createDisabledDateFunction = (minDate?: Date, maxDate?: Date) => {
  if (!minDate && !maxDate) return undefined;

  return (date: Date): boolean => {
    if (minDate && dayjs(date).isBefore(minDate, "day")) {
      return true;
    }
    if (maxDate && dayjs(date).isAfter(maxDate, "day")) {
      return true;
    }
    return false;
  };
};

const getRangeDatesText = (v: DateRangeValue): string => {
  const fromDate = dayjs(v.from).format("YYYY/MM/DD");
  const toDate = dayjs(v.to).format("YYYY/MM/DD");

  return `${fromDate} - ${toDate}`;
};

type DateRangeSelectContextType = {
  setIsOpen: (open: boolean) => void;
  applyRange: (range: DateRangeValue) => void;
  selectValue: DateRangePreset | "custom";
  value: DateRangeValue; // The applied range that Trigger displays
  minDate?: Date; // Minimum selectable date
  maxDate?: Date; // Maximum selectable date
  customMode: boolean;
};

const DateRangeSelectContext = createContext<DateRangeSelectContextType | null>(
  null,
);

export const useDateRangeSelectContext = () => {
  const context = useContext(DateRangeSelectContext);
  if (!context) {
    throw new Error(
      "DateRangeSelect components must be used within DateRangeSelect.Provider",
    );
  }
  return context;
};

// Helper function to get preset date ranges
const PRESET_DATE_RANGES: Record<
  Exclude<DateRangePreset, "customDates">,
  DateRangeValue
> = {
  today: {
    from: dayjs().startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  last3days: {
    from: dayjs().subtract(2, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  lastWeek: {
    from: dayjs().subtract(6, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  lastMonth: {
    from: dayjs().subtract(29, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
};

// Helper function to check if a range matches a preset
const getRangePreset = (range: DateRangeValue): DateRangePreset | null => {
  const presets: DateRangePreset[] = [
    "today",
    "last3days",
    "lastWeek",
    "lastMonth",
  ];

  for (const preset of presets) {
    const presetRange = PRESET_DATE_RANGES[preset];
    if (
      dayjs(range.from).isSame(presetRange.from, "day") &&
      dayjs(range.to).isSame(presetRange.to, "day")
    ) {
      return preset;
    }
  }

  return null;
};

// Helper function to get preset label
const PRESET_LABEL_MAP: Record<DateRangePreset, string> = {
  today: "Today",
  last3days: "Last 3 days",
  lastWeek: "Last week",
  lastMonth: "Last month",
};

// PresetOption Component
type PresetOptionProps = {
  preset: DateRangePreset;
  children?: React.ReactNode;
  className?: string;
};

const PresetOption: React.FC<PresetOptionProps> = ({
  preset,
  children,
  className,
}) => {
  return (
    <SelectItem value={preset} className={cn("text-sm font-normal", className)}>
      {children || PRESET_LABEL_MAP[preset]}
    </SelectItem>
  );
};

// CustomDatesOption Component
type CustomDatesOptionProps = {
  className?: string;
};

const CustomDatesOption: React.FC<CustomDatesOptionProps> = ({ className }) => {
  const { minDate, maxDate, applyRange, selectValue, value } =
    useDateRangeSelectContext();
  const [isCalendarOpen, setIsCalendarOpen] = React.useState(false);

  const handleDateSelect = (dateRange: DateRange | undefined) => {
    if (!dateRange || !dateRange.from) return;

    // If end date is not selected, set it to the same as start date
    const endDate = dateRange.to || dateRange.from;

    const newRange = {
      from: dateRange.from,
      to: endDate,
    };

    applyRange(newRange);
  };

  // Create disabled function using context values
  const disabled = React.useMemo(
    () => createDisabledDateFunction(minDate, maxDate),
    [minDate, maxDate],
  );

  const setIssCalendarOpen = (open: boolean) => {
    if (!open) {
      return;
    }

    setIsCalendarOpen(open);
  };

  const isSelected = selectValue === "custom";

  return (
    <>
      <Separator className="my-0.5" />
      <Popover open={isCalendarOpen} onOpenChange={setIssCalendarOpen}>
        <PopoverTrigger asChild>
          <div
            className={cn(
              "relative flex flex-col w-full min-w-[240px] cursor-default select-none justify-stretch rounded-sm py-2 hover:bg-accent hover:text-accent-foreground pl-8 pr-2 text-sm outline-none focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
              isSelected && "bg-accent text-accent-foreground min-w-[300px]",
              className,
            )}
          >
            <div className="flex items-center">
              {isSelected && (
                <span className="absolute left-2 flex size-3.5 items-center justify-center">
                  <Check className="size-4" />
                </span>
              )}
              <div className="flex w-full items-center justify-between">
                <span>Custom dates</span>
                <CalendarIcon className="ml-auto size-3.5 text-light-slate" />
              </div>
            </div>
            {isSelected && (
              <div className="comet-body-s mt-0.5 whitespace-pre-wrap text-light-slate">
                {getRangeDatesText(value)}
              </div>
            )}
          </div>
        </PopoverTrigger>
        <PopoverContent
          className={cn("w-auto p-0", className)}
          align="start"
          side="right"
          sideOffset={10}
        >
          <Calendar
            mode="range"
            selected={value}
            onSelect={handleDateSelect}
            numberOfMonths={1}
            className="rounded-md"
            aria-label="Select date range"
            disabled={disabled}
          />
        </PopoverContent>
      </Popover>
    </>
  );
};

// Trigger Component
type TriggerProps = {
  className?: string;
  placeholder?: string;
};

const Trigger: React.FC<TriggerProps> = ({
  className,
  placeholder = "Select date range",
}) => {
  const { value, customMode } = useDateRangeSelectContext();

  const displayText = React.useMemo(() => {
    if (customMode) {
      return getRangeDatesText(value);
    }

    const appliedPreset = getRangePreset(value);
    if (appliedPreset) {
      return PRESET_LABEL_MAP[appliedPreset];
    }

    return placeholder;
  }, [value, customMode, placeholder]);

  return (
    <SelectTrigger className={cn("w-auto min-w-40 h-8", className)}>
      <div className="flex items-center gap-2">
        <CalendarIcon className="size-3.5" />
        {displayText}
      </div>
    </SelectTrigger>
  );
};

// Popover Content Component
type ContentProps = {
  children: React.ReactNode;
  className?: string;
};

const Content: React.FC<ContentProps> = ({ children, className }) => {
  return (
    <SelectContent align="end" side="bottom" className={className}>
      {children}
    </SelectContent>
  );
};

type DateRangeSelectProps = {
  value?: DateRangeValue; // The currently applied range that Trigger displays
  onChangeValue?: (range: DateRangeValue) => void;
  children: React.ReactNode;
  minDate?: Date; // Minimum selectable date
  maxDate?: Date; // Maximum selectable date
};

const DateRangeSelectRoot: React.FC<DateRangeSelectProps> = ({
  value = DEFAULT_DATE_RANGE, // Default to DEFAULT_DATE_RANGE if no value provided
  onChangeValue,
  children,
  minDate,
  maxDate,
}) => {
  const [isOpen, setIsOpen] = React.useState(false);
  const [customMode, setCustomMode] = React.useState(false);

  // Determine the select value based on current range
  const selectValue = React.useMemo(() => {
    if (customMode) {
      return "custom";
    }

    const preset = getRangePreset(value);
    return preset || "custom";
  }, [value, customMode]);

  const setSelectValue = (newValue: DateRangePreset) => {
    const preset = newValue as DateRangePreset;
    const newRange = PRESET_DATE_RANGES[preset];
    setCustomMode(false);
    onChangeValue?.(newRange);
  };

  // Shared function to apply range changes
  const applyRange = (newRange: DateRangeValue) => {
    onChangeValue?.(newRange);
    setCustomMode(true);
  };

  const onOpenChange = (open: boolean) => {
    setIsOpen(open);
  };

  return (
    <DateRangeSelectContext.Provider
      value={{
        selectValue,
        setIsOpen,
        applyRange,
        value, // The applied range that Trigger displays
        minDate, // Pass minDate to context
        maxDate, // Pass maxDate to cont
        customMode,
      }}
    >
      <Select
        value={selectValue}
        onValueChange={setSelectValue}
        open={isOpen}
        onOpenChange={onOpenChange}
      >
        {children}
      </Select>
    </DateRangeSelectContext.Provider>
  );
};

type DateRangeSelectComponents = React.FC<DateRangeSelectProps> & {
  PresetOption: typeof PresetOption;
  CustomDatesOption: typeof CustomDatesOption;
  Trigger: typeof Trigger;
  Content: typeof Content;
};

const DateRangeSelect: DateRangeSelectComponents = Object.assign(
  DateRangeSelectRoot,
  {
    PresetOption,
    CustomDatesOption,
    Trigger,
    Content,
  },
);

export default DateRangeSelect;
