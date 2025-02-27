import React, { useMemo, useState, useRef, useCallback } from "react";
import { Calendar as CalendarIcon } from "lucide-react";
import debounce from "lodash/debounce";

import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";
import dayjs from "dayjs";

type TimeRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const TimeRow: React.FunctionComponent<TimeRowProps> = ({
  filter,
  onChange,
}) => {
  const [open, setOpen] = useState(false);
  const [inputValue, setInputValue] = useState(() => 
    filter.value ? formatDate(filter.value as string) : ""
  );
  const lastFilterValue = useRef(filter.value);
  
  const date = useMemo(
    () => {
      if (!filter.value) return undefined;
      const parsed = dayjs(filter.value);
      return parsed.isValid() ? parsed.toDate() : undefined;
    },
    [filter.value],
  );

  // Only update input value when filter value changes from external sources
  React.useEffect(() => {
    if (filter.value !== lastFilterValue.current) {
      setInputValue(filter.value ? formatDate(filter.value as string) : "");
      lastFilterValue.current = filter.value;
    }
  }, [filter.value]);

  const debouncedUpdateFilter = useCallback(
    debounce((newValue: string | "") => {
      // Don't update if the value hasn't changed
      if (newValue === filter.value) return;

      // Validate the date before updating
      if (newValue && !dayjs(newValue).isValid()) return;

      lastFilterValue.current = newValue;
      onChange({
        ...filter,
        value: newValue,
      });
    }, 500),
    [filter, onChange]
  );

  const onSelectDate = (value: Date | undefined) => {
    if (!value) {
      setInputValue("");
      debouncedUpdateFilter("");
      return;
    }

    try {
      // Preserve the current time when changing date
      if (date) {
        value.setHours(date.getHours());
        value.setMinutes(date.getMinutes());
      } else {
        // Set default time to start of day if no previous time
        value.setHours(0);
        value.setMinutes(0);
        value.setSeconds(0);
        value.setMilliseconds(0);
      }

      const newValue = value.toISOString();
      setInputValue(formatDate(newValue));
      debouncedUpdateFilter(newValue);
    } catch (error) {
      console.error("Invalid date:", error);
    }
    setOpen(false);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;
    setInputValue(newValue);
    
    if (!newValue) {
      debouncedUpdateFilter("");
      return;
    }
    
    const parsedDate = dayjs(newValue, ["MM/DD/YY HH:mm A", "MM/DD/YYYY HH:mm A"], true);
    
    if (parsedDate.isValid()) {
      try {
        const isoString = parsedDate.toISOString();
        debouncedUpdateFilter(isoString);
      } catch (error) {
        console.error("Invalid date:", error);
      }
    }
  };

  // Cleanup debounce on unmount
  React.useEffect(() => {
    return () => {
      debouncedUpdateFilter.cancel();
    };
  }, [debouncedUpdateFilter]);

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
        <div className="relative flex items-center">
          <div className="relative w-full">
            <Input
              value={inputValue}
              onChange={handleInputChange}
              placeholder="MM/DD/YY HH:mm A"
              className="w-full pr-10"
            />
            <Popover open={open} onOpenChange={setOpen}>
              <PopoverTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="absolute right-0 top-0 h-full px-3 hover:bg-transparent"
                >
                  <CalendarIcon className="size-4 opacity-50" />
                </Button>
              </PopoverTrigger>
              <PopoverContent className="w-auto p-0" align="end">
                <Calendar
                  mode="single"
                  selected={date}
                  onSelect={onSelectDate}
                  initialFocus
                />
              </PopoverContent>
            </Popover>
          </div>
        </div>
      </td>
    </>
  );
};

export default TimeRow;
