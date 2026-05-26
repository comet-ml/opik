import React from "react";
import FilterChipPopover from "@/shared/filter-chips/chips/FilterChipPopover";
import NumericChipPopoverContent from "@/shared/filter-chips/chips/NumericChip/NumericChipPopoverContent";
import { formatNumericSummary } from "@/shared/filter-chips/chips/NumericChip/NumericChip.logic";
import {
  NumericChipDefinition,
  NumericChipValue,
} from "@/shared/filter-chips/types";

interface NumericChipProps {
  definition: NumericChipDefinition;
  value: NumericChipValue | undefined;
  onApply: (value: NumericChipValue) => void;
  onClear: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const NumericChip: React.FC<NumericChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
  open,
  onOpenChange,
}) => (
  <FilterChipPopover
    label={definition.label}
    valueSummary={formatNumericSummary(value, definition)}
    open={open}
    onOpenChange={onOpenChange}
    onClear={onClear}
  >
    <NumericChipPopoverContent
      definition={definition}
      value={value}
      onApply={onApply}
      onClear={onClear}
    />
  </FilterChipPopover>
);

export default NumericChip;
