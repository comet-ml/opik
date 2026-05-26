import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";
import PseudoSearchChipPopoverContent from "@/shared/filter-chips/chips/PseudoSearchChip/PseudoSearchChipPopoverContent";
import { formatPseudoSearchSummary } from "@/shared/filter-chips/chips/PseudoSearchChip/PseudoSearchChip.logic";
import {
  PseudoSearchChipDefinition,
  PseudoSearchChipValue,
} from "@/shared/filter-chips/types";

interface PseudoSearchChipProps {
  definition: PseudoSearchChipDefinition;
  value: PseudoSearchChipValue | undefined;
  onApply: (value: PseudoSearchChipValue) => void;
  onClear: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const PseudoSearchChip: React.FC<PseudoSearchChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
  open,
  onOpenChange,
}) => {
  const summary = formatPseudoSearchSummary(value, definition);

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
        <PseudoSearchChipPopoverContent
          definition={definition}
          value={value}
          onApply={onApply}
          onClear={onClear}
          onCommit={() => onOpenChange(false)}
        />
      </PopoverContent>
    </Popover>
  );
};

export default PseudoSearchChip;
