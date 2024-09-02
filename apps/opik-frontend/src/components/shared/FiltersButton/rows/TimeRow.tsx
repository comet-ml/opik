import React, { useMemo } from "react";
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

type TimeRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const TimeRow: React.FunctionComponent<TimeRowProps> = ({
  filter,
  onChange,
}) => {
  const date = useMemo(
    () => (dayjs(filter.value).isValid() ? new Date(filter.value) : undefined),
    [filter.value],
  );

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
        <Popover>
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
              onSelect={(value) => {
                onChange({
                  ...filter,
                  value: value ? value.toISOString() : "",
                });
              }}
              initialFocus
            />
          </PopoverContent>
        </Popover>
      </td>
    </>
  );
};

export default TimeRow;
