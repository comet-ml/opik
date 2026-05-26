import React from "react";
import FilterChipPopover from "@/shared/filter-chips/chips/FilterChipPopover";
import SingleSelectChipPopoverContent from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChipPopoverContent";
import { formatSingleSelectSummary } from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChip.logic";
import {
  SingleSelectChipDefinition,
  SingleSelectChipValue,
} from "@/shared/filter-chips/types";

interface SingleSelectChipProps {
  definition: SingleSelectChipDefinition;
  value: SingleSelectChipValue | undefined;
  onApply: (value: SingleSelectChipValue) => void;
  onClear: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const SingleSelectChip: React.FC<SingleSelectChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
  open,
  onOpenChange,
}) => (
  <FilterChipPopover
    label={definition.label}
    valueSummary={formatSingleSelectSummary(value, definition)}
    open={open}
    onOpenChange={onOpenChange}
  >
    <SingleSelectChipPopoverContent
      definition={definition}
      value={value}
      onSelect={(next) => {
        onApply(next);
        onOpenChange(false);
      }}
      onClear={onClear}
    />
  </FilterChipPopover>
);

export default SingleSelectChip;
