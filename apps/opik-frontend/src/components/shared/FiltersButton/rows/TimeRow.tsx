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
  const [pendingDate, setPendingDate] = useState<Date | undefined>(undefined);
  const [pendingTime, setPendingTime] = useState<string>("00:00");
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
      if (newValue === filter.value) return;
      if (newValue && !dayjs(newValue).isValid()) return;

      lastFilterValue.current = newValue;
      onChange({
        ...filter,
        value: newValue,
      });
    }, 500),
    [filter, onChange]
  );

  const getTimeFromDate = (date: Date | undefined) => {
    if (!date) return "00:00";
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  };

  const onSelectDate = (value: Date | undefined) => {
    if (!value) {
      setPendingDate(undefined);
      return;
    }

    try {
      // If there's a pending date, preserve its time
      if (pendingDate) {
        value.setHours(pendingDate.getHours());
        value.setMinutes(pendingDate.getMinutes());
      } else if (date) {
        // If no pending date but we have a current date, use its time
        value.setHours(date.getHours());
        value.setMinutes(date.getMinutes());
      } else {
        // Default to start of day
        value.setHours(0);
        value.setMinutes(0);
        value.setSeconds(0);
        value.setMilliseconds(0);
      }

      setPendingDate(value);
    } catch (error) {
      console.error("Invalid date:", error);
    }
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

  const handleTimeChange = (timeString: string) => {
    setPendingTime(timeString);
    if (!pendingDate) return;

    try {
      const [hours, minutes] = timeString.split(':').map(Number);
      const newDate = new Date(pendingDate);
      newDate.setHours(hours);
      newDate.setMinutes(minutes);
      setPendingDate(newDate);
    } catch (error) {
      console.error("Invalid time:", error);
    }
  };

  // Handle popover close
  const handleOpenChange = (isOpen: boolean) => {
    setOpen(isOpen);
    
    if (!isOpen && pendingDate) {
      // When closing, apply the pending changes
      const newValue = pendingDate.toISOString();
      setInputValue(formatDate(newValue));
      debouncedUpdateFilter(newValue);
      
      // Reset pending states
      setPendingDate(undefined);
      setPendingTime("00:00");
    }
  };

  // Initialize pending values when opening the popover
  React.useEffect(() => {
    if (open && date) {
      setPendingDate(new Date(date));
      setPendingTime(getTimeFromDate(date));
    }
  }, [open, date]);

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
            <Popover open={open} onOpenChange={handleOpenChange}>
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
                  selected={pendingDate || date}
                  onSelect={onSelectDate}
                  initialFocus
                  selectedTime={pendingTime}
                  onTimeChange={handleTimeChange}
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
