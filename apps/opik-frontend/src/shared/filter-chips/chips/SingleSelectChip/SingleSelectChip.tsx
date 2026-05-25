import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";
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
}) => {
  const summary = formatSingleSelectSummary(value, definition);

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
        <SingleSelectChipPopoverContent
          definition={definition}
          value={value}
          onSelect={(next) => {
            onApply(next);
            onOpenChange(false);
          }}
          onClear={onClear}
        />
      </PopoverContent>
    </Popover>
  );
};

export default SingleSelectChip;
