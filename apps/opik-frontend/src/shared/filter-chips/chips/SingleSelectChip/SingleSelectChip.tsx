import React from "react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
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
}) => (
  <DropdownMenu open={open} onOpenChange={onOpenChange}>
    <DropdownMenuTrigger asChild>
      <BaseChip
        label={definition.label}
        valueSummary={formatSingleSelectSummary(value, definition)}
        isOpen={open}
        onClear={onClear}
      />
    </DropdownMenuTrigger>
    <DropdownMenuContent
      align="start"
      sideOffset={4}
      collisionPadding={16}
      className="flex max-h-[var(--radix-dropdown-menu-content-available-height)] w-[238px] flex-col rounded-md border border-border bg-background p-1 shadow-sm"
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
    </DropdownMenuContent>
  </DropdownMenu>
);

export default SingleSelectChip;
