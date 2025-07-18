import React, { createContext, useContext } from "react";
import { Calendar, CalendarProps } from "@/components/ui/calendar";
import { Button, ButtonProps } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import dayjs from "dayjs";
import { DateRange } from "react-day-picker";
import { useForm, FormProvider, useFormContext } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { CalendarIcon } from "lucide-react";

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

// Helper function to create date range form schema with global refinement
const createDateRangeSchema = (minDate?: Date, maxDate?: Date) => {
  return z
    .object({
      from: z.string().min(1, "Start date is required"),
      to: z.string().min(1, "End date is required"),
    })
    .superRefine((data, ctx) => {
      const fromDate = parseDateFromInput(data.from);
      const toDate = parseDateFromInput(data.to);

      // Check date format validity
      if (!fromDate && data.from.trim()) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Invalid date format. Use YYYY/MM/DD",
          path: ["from"],
        });
      }

      if (!toDate && data.to.trim()) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Invalid date format. Use YYYY/MM/DD",
          path: ["to"],
        });
      }

      // Only proceed with range validations if both dates are valid
      if (fromDate && toDate) {
        // Check date range logic
        if (dayjs(fromDate).isAfter(toDate, "day")) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Start date must be before or equal to end date",
            path: ["from"],
          });
        }

        // Check min date constraint
        if (minDate && dayjs(fromDate).isBefore(minDate, "day")) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Start date must be on or after ${formatDateForInput(
              minDate,
            )}`,
            path: ["from"],
          });
        }

        // Check max date constraint for 'to' field
        if (maxDate && dayjs(toDate).isAfter(maxDate, "day")) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `End date must be on or before ${formatDateForInput(
              maxDate,
            )}`,
            path: ["to"],
          });
        }

        // Also check min date constraint for 'to' field (edge case)
        if (minDate && dayjs(toDate).isBefore(minDate, "day")) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `End date must be on or after ${formatDateForInput(
              minDate,
            )}`,
            path: ["to"],
          });
        }
      }
    });
};

type DateRangeFormValues = z.infer<ReturnType<typeof createDateRangeSchema>>;

type DateRangeSelectContextType = {
  range: DateRangeValue;
  setRange: (range: DateRangeValue) => void;
  activePreset: DateRangePreset | null;
  onChangeValue?: (range: DateRangeValue) => void;
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  applyRange: (range: DateRangeValue) => void;
  value: DateRangeValue; // The applied range that Trigger displays
  minDate?: Date; // Minimum selectable date
  maxDate?: Date; // Maximum selectable date
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
const PRESET_DATE_RANGES: Record<DateRangePreset, DateRangeValue> = {
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

// Helper functions for date formatting and parsing
const formatDateForInput = (date: Date): string => {
  return dayjs(date).format("YYYY/MM/DD");
};

const parseDateFromInput = (dateString: string): Date | null => {
  if (!dateString.trim()) return null;

  const parsed = dayjs(dateString, "YYYY/MM/DD", true);
  if (parsed.isValid()) {
    return parsed.toDate();
  }

  return null;
};

// Individual Quick Button Component
type QuickButtonProps = {
  preset: DateRangePreset;
  children: React.ReactNode;
  className?: string;
};

const QuickButton: React.FC<QuickButtonProps> = ({
  preset,
  children,
  className,
}) => {
  const { activePreset, applyRange } = useDateRangeSelectContext();

  const handleQuickSelect = () => {
    const newRange = PRESET_DATE_RANGES[preset];
    applyRange(newRange);
  };

  const isActive = activePreset === preset;

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className={cn(
        "justify-center text-sm font-normal",
        isActive && "bg-primary-100 hover:bg-primary-100",
        className,
      )}
      onClick={handleQuickSelect}
      aria-pressed={isActive}
    >
      {children}
    </Button>
  );
};

// Calendar Component
type CalendarViewProps = {
  className?: string;
} & Omit<CalendarProps, "mode" | "selected" | "onSelect" | "disabled">;

const CalendarView: React.FC<CalendarViewProps> = ({ className, ...props }) => {
  const { range, setRange, minDate, maxDate } = useDateRangeSelectContext();

  const handleDateSelect = (dateRange: DateRange | undefined) => {
    if (!dateRange || !dateRange.from) return;

    // If end date is not selected, set it to the same as start date
    const endDate = dateRange.to || dateRange.from;

    setRange({
      from: dateRange.from,
      to: endDate,
    });
  };

  // Create disabled function using context values
  const disabled = React.useMemo(
    () => createDisabledDateFunction(minDate, maxDate),
    [minDate, maxDate],
  );

  return (
    <Calendar
      mode="range"
      selected={range}
      onSelect={handleDateSelect}
      numberOfMonths={1}
      className={cn("rounded-md", className)}
      aria-label="Select date range"
      disabled={disabled}
      {...props}
    />
  );
};

// Date Input Components
type FromDateInputProps = {
  className?: string;
};

const FromDateInput: React.FC<FromDateInputProps> = ({ className }) => {
  const { setRange, range } = useDateRangeSelectContext();
  const form = useFormContext<DateRangeFormValues>();

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;
    form.setValue("from", newValue);

    const isValid = await form.trigger();

    if (!isValid) {
      return;
    }
    const fromDate = parseDateFromInput(newValue);
    if (!fromDate) {
      return;
    }

    setRange({
      ...range,
      from: fromDate,
    });
  };

  return (
    <FormField
      name="from"
      render={({ field, fieldState }) => (
        <FormItem className={className}>
          <FormLabel
            htmlFor="from-date"
            className="comet-body-xs text-xs text-muted-foreground"
          >
            Start date
          </FormLabel>
          <FormControl>
            <Input
              id="from-date"
              type="text"
              placeholder="YYYY/MM/DD"
              value={field.value}
              onChange={handleChange}
              dimension="sm"
              className={cn(
                fieldState.error &&
                  "border-destructive focus-visible:ring-destructive",
              )}
            />
          </FormControl>
        </FormItem>
      )}
    />
  );
};

type ToDateInputProps = {
  className?: string;
};

const ToDateInput: React.FC<ToDateInputProps> = ({ className }) => {
  const { setRange, range } = useDateRangeSelectContext();
  const form = useFormContext<DateRangeFormValues>();

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;

    form.setValue("to", newValue);

    const isValid = await form.trigger();

    if (!isValid) {
      return;
    }

    const toDate = parseDateFromInput(newValue);
    if (!toDate) {
      return;
    }

    setRange({
      ...range,
      to: toDate,
    });
  };

  return (
    <FormField
      name="to"
      render={({ field, fieldState }) => (
        <FormItem className={className}>
          <FormLabel
            htmlFor="to-date"
            className="comet-body-xs text-xs text-muted-foreground"
          >
            End date
          </FormLabel>
          <FormControl>
            <Input
              id="to-date"
              type="text"
              placeholder="YYYY/MM/DD"
              value={field.value}
              onChange={handleChange}
              dimension="sm"
              className={cn(
                fieldState.error &&
                  "border-destructive focus-visible:ring-destructive",
              )}
            />
          </FormControl>
        </FormItem>
      )}
    />
  );
};

// ErrorMessage Component
type ErrorMessageProps = {
  className?: string;
};

const ErrorMessage: React.FC<ErrorMessageProps> = ({ className }) => {
  const form = useFormContext<DateRangeFormValues>();
  const errors = form.formState.errors;

  // Get the last error (prioritize 'to' field errors, then 'from' field errors)
  const lastError = React.useMemo(() => {
    if (errors.to?.message) {
      return errors.to.message;
    }
    if (errors.from?.message) {
      return errors.from.message;
    }
    return null;
  }, [errors]);

  if (!lastError) {
    return null;
  }

  return (
    <div className={cn("comet-body-xs text-xs text-destructive", className)}>
      {lastError}
    </div>
  );
};

type CancelButtonProps = {
  className?: string;
  children?: React.ReactNode;
};

const CancelButton: React.FC<CancelButtonProps> = ({
  className,
  children = "Cancel",
}) => {
  const { setIsOpen } = useDateRangeSelectContext();

  const handleClick = () => {
    setIsOpen(false);
  };

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      onClick={handleClick}
      className={className}
    >
      {children}
    </Button>
  );
};

type ApplyButtonProps = {
  className?: string;
  children?: React.ReactNode;
};

const ApplyButton: React.FC<ApplyButtonProps> = ({
  className,
  children = "Apply",
}) => {
  const form = useFormContext<DateRangeFormValues>();
  const { isValid } = form.formState;

  return (
    <Button
      type="submit"
      form="date-range-select-form"
      size="sm"
      disabled={!isValid}
      className={className}
    >
      {children}
    </Button>
  );
};

// Popover Root Component
type PopoverRootProps = {
  children: React.ReactNode;
};

const PopoverRoot: React.FC<PopoverRootProps> = ({ children }) => {
  const { isOpen, setIsOpen } = useDateRangeSelectContext();

  return (
    <Popover open={isOpen} onOpenChange={setIsOpen}>
      {children}
    </Popover>
  );
};

// Trigger Component
type TriggerProps = {
  className?: string;
  placeholder?: string;
  variant?: ButtonProps["variant"];
  size?: ButtonProps["size"];
};

const Trigger: React.FC<TriggerProps> = ({
  className,
  placeholder = "Select date range",
  variant = "outline",
  size = "sm",
}) => {
  const { value } = useDateRangeSelectContext();

  const displayText = React.useMemo(() => {
    // Check if the applied value matches a preset
    const appliedPreset = getRangePreset(value);
    if (appliedPreset) {
      return PRESET_LABEL_MAP[appliedPreset];
    }

    const fromDate = formatDateForInput(value.from);
    const toDate = formatDateForInput(value.to);

    if (dayjs(value.from).isSame(value.to, "day")) {
      return fromDate;
    }

    return `${fromDate} - ${toDate}`;
  }, [value]);

  return (
    <PopoverTrigger asChild>
      <Button
        variant={variant}
        size={size}
        className={cn(!value && "text-muted-foreground", className)}
        type="button"
      >
        <CalendarIcon className="mr-2 size-3.5" />
        {value ? displayText : placeholder}
      </Button>
    </PopoverTrigger>
  );
};

// Popover Content Component
type ContentProps = {
  children: React.ReactNode;
  className?: string;
  align?: "start" | "center" | "end";
  side?: "top" | "right" | "bottom" | "left";
};

const Content: React.FC<ContentProps> = ({
  children,
  className,
  align = "end",
  side = "bottom",
}) => {
  return (
    <PopoverContent
      className={cn("w-auto p-0 shadow-none", className)}
      align={align}
      side={side}
    >
      {children}
    </PopoverContent>
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
  const [range, setRangeState] = React.useState<DateRangeValue>(value);
  const [isOpen, setIsOpenState] = React.useState(false);

  // Create schema with min/max date constraints
  const dateRangeSchema = React.useMemo(
    () => createDateRangeSchema(minDate, maxDate),
    [minDate, maxDate],
  );

  const form = useForm<DateRangeFormValues>({
    resolver: zodResolver(dateRangeSchema),
    defaultValues: {
      from: formatDateForInput(value.from),
      to: formatDateForInput(value.to),
    },
  });

  // Middleware for setIsOpen that resets form when closing
  const setIsOpen = (open: boolean) => {
    if (open) {
      form.reset({
        from: formatDateForInput(value.from),
        to: formatDateForInput(value.to),
      });
      setRangeState(value);
    }
    setIsOpenState(open);
  };

  // Sync form values when range changes (from calendar or quick buttons)
  const setRange = (newRange: DateRangeValue) => {
    setRangeState(newRange);
    form.setValue("from", formatDateForInput(newRange.from));
    form.setValue("to", formatDateForInput(newRange.to));
    form.trigger(); // Validate the form
  };

  // Shared function to apply range changes
  const applyRange = (newRange: DateRangeValue) => {
    setRangeState(newRange);
    onChangeValue?.(newRange);
    setIsOpen(false);
  };

  // Handle form submission (when Apply is clicked)
  const handleFormSubmit = (data: DateRangeFormValues) => {
    const fromDate = parseDateFromInput(data.from);
    const toDate = parseDateFromInput(data.to);

    if (fromDate && toDate) {
      const newRange: DateRangeValue = {
        from: fromDate,
        to: toDate,
      };
      applyRange(newRange);
    }
  };

  const activePreset = getRangePreset(range);

  return (
    <DateRangeSelectContext.Provider
      value={{
        range,
        setRange,
        activePreset,
        onChangeValue,
        isOpen,
        setIsOpen,
        applyRange,
        value, // The applied range that Trigger displays
        minDate, // Pass minDate to context
        maxDate, // Pass maxDate to context
      }}
    >
      <FormProvider {...form}>
        <form
          id="date-range-select-form"
          onSubmit={form.handleSubmit(handleFormSubmit)}
        >
          {children}
        </form>
      </FormProvider>
    </DateRangeSelectContext.Provider>
  );
};

type DateRangeSelectComponents = React.FC<DateRangeSelectProps> & {
  QuickButton: typeof QuickButton;
  Calendar: typeof CalendarView;
  FromDateInput: typeof FromDateInput;
  ToDateInput: typeof ToDateInput;
  ErrorMessage: typeof ErrorMessage;
  CancelButton: typeof CancelButton;
  ApplyButton: typeof ApplyButton;
  Popover: typeof PopoverRoot;
  Trigger: typeof Trigger;
  Content: typeof Content;
};

const DateRangeSelect: DateRangeSelectComponents = Object.assign(
  DateRangeSelectRoot,
  {
    QuickButton,
    Calendar: CalendarView,
    FromDateInput,
    ToDateInput,
    ErrorMessage,
    CancelButton,
    ApplyButton,
    Popover: PopoverRoot,
    Trigger,
    Content,
  },
);

export default DateRangeSelect;
