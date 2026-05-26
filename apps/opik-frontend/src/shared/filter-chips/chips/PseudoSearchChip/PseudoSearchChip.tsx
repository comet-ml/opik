import React from "react";
import FilterChipPopover from "@/shared/filter-chips/chips/FilterChipPopover";
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
}) => (
  <FilterChipPopover
    label={definition.label}
    valueSummary={formatPseudoSearchSummary(value, definition)}
    open={open}
    onOpenChange={onOpenChange}
  >
    <PseudoSearchChipPopoverContent
      definition={definition}
      value={value}
      onApply={onApply}
      onClear={onClear}
      onCommit={() => onOpenChange(false)}
    />
  </FilterChipPopover>
);

export default PseudoSearchChip;
