import React from "react";
import FilterChipPopover from "@/shared/filter-chips/chips/FilterChipPopover";
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
}) => (
  <FilterChipPopover
    label={definition.label}
    valueSummary={formatTimeSummary(value)}
    open={open}
    onOpenChange={onOpenChange}
    onClear={onClear}
    contentProps={{ onOpenAutoFocus: (event) => event.preventDefault() }}
  >
    <TimeChipPopoverContent
      fieldLabel={definition.label}
      value={value}
      onApply={onApply}
      onClear={onClear}
    />
  </FilterChipPopover>
);

export default TimeChip;
