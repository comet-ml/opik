import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";
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
}) => {
  const summary = formatNumericSummary(value, definition);

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
        <NumericChipPopoverContent
          definition={definition}
          value={value}
          onApply={onApply}
          onClear={onClear}
        />
      </PopoverContent>
    </Popover>
  );
};

export default NumericChip;
