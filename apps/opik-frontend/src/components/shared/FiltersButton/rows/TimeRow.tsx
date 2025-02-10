import React, { useMemo, useState } from "react";
import { Calendar as CalendarIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";
import dayjs from "dayjs";
import { SelectSingleEventHandler } from "react-day-picker";

type TimeRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const TimeRow: React.FunctionComponent<TimeRowProps> = ({
  filter,
  onChange,
}) => {
  const [open, setOpen] = useState(false);
  const date = useMemo(
    () => (dayjs(filter.value).isValid() ? new Date(filter.value) : undefined),
    [filter.value],
  );

  const onSelectDate: SelectSingleEventHandler = (value) => {
    onChange({
      ...filter,
      value: value ? value.toISOString() : "",
    });
    setOpen(false);
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
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <Button
              variant={"outline"}
              className={cn(
                "w-full min-w-40 justify-start text-left font-normal",
                !filter.value && "text-muted-foreground",
              )}
            >
              <CalendarIcon className="mr-2 size-4" />
              {filter.value ? (
                formatDate(filter.value as string)
              ) : (
                <span>Pick a date</span>
              )}
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-auto p-0">
            <Calendar
              mode="single"
              selected={date}
              onSelect={onSelectDate}
              initialFocus
            />
          </PopoverContent>
        </Popover>
      </td>
    </>
  );
};

export default TimeRow;
