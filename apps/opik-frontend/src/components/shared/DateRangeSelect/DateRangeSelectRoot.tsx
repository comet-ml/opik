import React, { useCallback } from "react";
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
import { DateRange } from "react-day-picker";
import { CalendarIcon, Check } from "lucide-react";
import { Separator } from "@/components/ui/separator";
import { DateRangePreset, DateRangeValue } from "./types";
import {
  getDisabledDates,
  getRangeDatesText,
  getRangePreset,
  customDayClick,
} from "./utils";
import { PRESET_DATE_RANGES, PRESET_LABEL_MAP } from "./constants";
import {
  DateRangeSelectContext,
  useDateRangeSelectContext,
} from "./DateRangeSelectContext";

type PresetOptionProps = {
  value: DateRangePreset;
  className?: string;
};

const PresetOption: React.FC<PresetOptionProps> = ({ value, className }) => {
  return (
    <SelectItem value={value} className={cn("text-sm font-normal", className)}>
      {PRESET_LABEL_MAP[value]}
    </SelectItem>
  );
};

type CustomDatesOptionProps = {
  className?: string;
};

const CustomDatesOption: React.FC<CustomDatesOptionProps> = ({ className }) => {
  const { minDate, maxDate, setCustomRange, selectValue, value } =
    useDateRangeSelectContext();
  const [date, setDate] = React.useState<DateRange | undefined>(value);
  const [isCalendarOpen, setIsCalendarOpen] = React.useState(false);
  const isSelected = selectValue === "custom";

  const handleDayClick = (day: Date) => {
    setDate((prev) => customDayClick(prev, day, setCustomRange));
  };

  const disabledDates = React.useMemo(
    () => getDisabledDates(minDate, maxDate),
    [minDate, maxDate],
  );

  const handleSetIsCalendarOpen = (open: boolean) => {
    if (open) {
      setDate(value);
    }

    setIsCalendarOpen(open);
  };

  return (
    <>
      <Separator className="my-0.5" />
      <Popover
        open={isCalendarOpen}
        onOpenChange={handleSetIsCalendarOpen}
        modal
      >
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
            selected={date}
            onDayClick={handleDayClick}
            numberOfMonths={1}
            className="rounded-md"
            aria-label="Select date range"
            disabled={disabledDates}
          />
        </PopoverContent>
      </Popover>
    </>
  );
};

type TriggerProps = {
  className?: string;
};

const Trigger: React.FC<TriggerProps> = ({ className }) => {
  const { value } = useDateRangeSelectContext();

  const displayText = React.useMemo(() => {
    const appliedPreset = getRangePreset(value);

    if (!appliedPreset) {
      return getRangeDatesText(value);
    }

    return PRESET_LABEL_MAP[appliedPreset];
  }, [value]);

  return (
    <SelectTrigger className={cn("w-auto min-w-40 h-8", className)}>
      <div className="flex items-center gap-2">
        <CalendarIcon className="size-3.5" />
        {displayText}
      </div>
    </SelectTrigger>
  );
};

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
  value: DateRangeValue;
  onChangeValue: (range: DateRangeValue) => void;
  children: React.ReactNode;
  minDate?: Date;
  maxDate?: Date;
};

type DateRangeSelectComponents = React.FC<DateRangeSelectProps> & {
  PresetOption: typeof PresetOption;
  CustomDatesOption: typeof CustomDatesOption;
  Trigger: typeof Trigger;
  Content: typeof Content;
};

const DateRangeSelectRoot: DateRangeSelectComponents = ({
  value,
  onChangeValue,
  children,
  minDate,
  maxDate,
}) => {
  const [isOpen, setIsOpen] = React.useState(false);

  const selectValue = React.useMemo(
    () => getRangePreset(value) || "custom",
    [value],
  );

  const setPresetRange = useCallback(
    (preset: DateRangePreset) => {
      const newRange = PRESET_DATE_RANGES[preset];
      onChangeValue(newRange);
    },
    [onChangeValue],
  );

  const setCustomRange = useCallback(
    (newRange: DateRangeValue) => {
      onChangeValue(newRange);
      setIsOpen(false);
    },
    [onChangeValue],
  );

  return (
    <DateRangeSelectContext.Provider
      value={{
        selectValue,
        setCustomRange,
        value,
        minDate,
        maxDate,
      }}
    >
      <Select
        value={selectValue}
        onValueChange={setPresetRange}
        open={isOpen}
        onOpenChange={setIsOpen}
      >
        {children}
      </Select>
    </DateRangeSelectContext.Provider>
  );
};

DateRangeSelectRoot.PresetOption = PresetOption;
DateRangeSelectRoot.CustomDatesOption = CustomDatesOption;
DateRangeSelectRoot.Trigger = Trigger;
DateRangeSelectRoot.Content = Content;

export default DateRangeSelectRoot;
