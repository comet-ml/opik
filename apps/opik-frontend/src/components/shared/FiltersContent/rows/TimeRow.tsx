import React, { useState } from "react";
import { Calendar as CalendarIcon } from "lucide-react";
import dayjs from "dayjs";

import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import TimePicker from "@/components/shared/TimePicker/TimePicker";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";
import { formatDate, isStringValidFormattedDate } from "@/lib/date";
import { cn } from "@/lib/utils";

type TimeRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const TimeRow: React.FunctionComponent<TimeRowProps> = ({
  filter,
  onChange,
}) => {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState(false);
  const [date, setDate] = useState<Date | undefined>(
    dayjs(filter.value).isValid() ? new Date(filter.value) : undefined,
  );

  const [dateString, setDateString] = useState<string>(
    dayjs(filter.value).isValid() ? formatDate(filter.value as string) : "",
  );

  const onSelectDate = (value: Date | undefined) => {
    setDate(value);
    if (value) {
      setDateString(formatDate(value.toISOString()));
      setError(false);
    }
  };

  const onValueChange = (value: string) => {
    setDateString(value);

    const isValid = isStringValidFormattedDate(value);

    if (isValid) {
      const parsedDate = dayjs(
        value,
        ["MM/DD/YY HH:mm A", "MM/DD/YYYY HH:mm A"],
        true,
      );
      setDate(parsedDate.toDate());
      onChange({
        ...filter,
        value: parsedDate.toISOString(),
      });
    }
    setError(!isValid);
  };

  const onOpenChange = (open: boolean) => {
    if (!open && date) {
      onValueChange(formatDate(date.toISOString()));
    }

    setOpen(open);
  };

  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            OPERATORS_MAP[filter.type as COLUMN_TYPE] ?? DEFAULT_OPERATORS
          }
          onSelect={(o) => onChange({ ...filter, operator: o })}
        />
      </td>
      <td className="p-1">
        <Popover open={open} onOpenChange={onOpenChange}>
          <div className="relative w-full">
            <DebounceInput
              className={cn("pr-10", {
                "border-destructive focus-visible:border-destructive": error,
              })}
              onValueChange={(value) => onValueChange(value as string)}
              placeholder="MM/DD/YY HH:mm A"
              value={dateString}
              delay={500}
            />
            <div className="absolute right-2.5 top-1/2 -translate-y-1/2">
              <PopoverTrigger asChild>
                <Button variant="ghost" size="icon-sm">
                  <CalendarIcon />
                </Button>
              </PopoverTrigger>
            </div>
          </div>
          <PopoverContent
            className="max-h-[calc(var(--radix-popper-available-height)-4px)] w-auto overflow-y-auto p-0"
            sideOffset={8}
          >
            <Calendar
              mode="single"
              selected={date}
              onSelect={onSelectDate}
              initialFocus
            />
            <div className="border-t border-border p-3">
              <TimePicker date={date} setDate={onSelectDate} />
            </div>
          </PopoverContent>
        </Popover>
      </td>
    </>
  );
};

export default TimeRow;
