import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";
import TimeChipPopoverContent from "@/shared/filter-chips/chips/TimeChip/TimeChipPopoverContent";
import { formatTimeSummary } from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import { TimeChipDefinition, TimeChipValue } from "@/shared/filter-chips/types";

interface TimeChipProps {
  definition: TimeChipDefinition;
  value: TimeChipValue | undefined;
  onApply: (value: TimeChipValue) => void;
  onClear: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const TimeChip: React.FC<TimeChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
  open,
  onOpenChange,
}) => {
  const summary = formatTimeSummary(value);

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <BaseChip
          label={definition.label}
          valueSummary={summary}
          isOpen={open}
        />
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={4}
        className="w-auto rounded-md border border-border bg-background p-0 shadow-sm"
      >
        <TimeChipPopoverContent
          value={value}
          onApply={onApply}
          onClear={onClear}
        />
      </PopoverContent>
    </Popover>
  );
};

export default TimeChip;
