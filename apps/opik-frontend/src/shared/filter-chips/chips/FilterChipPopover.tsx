import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";

type PopoverContentProps = React.ComponentPropsWithoutRef<
  typeof PopoverContent
>;

interface FilterChipPopoverProps {
  label: string;
  valueSummary?: string | null;
  valueSummaryFull?: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onClear?: () => void;
  contentProps?: Pick<PopoverContentProps, "onOpenAutoFocus">;
  children: React.ReactNode;
}

const FilterChipPopover: React.FC<FilterChipPopoverProps> = ({
  label,
  valueSummary,
  valueSummaryFull,
  open,
  onOpenChange,
  onClear,
  contentProps,
  children,
}) => (
  <Popover open={open} onOpenChange={onOpenChange}>
    <PopoverTrigger asChild>
      <BaseChip
        label={label}
        valueSummary={valueSummary}
        valueSummaryFull={valueSummaryFull}
        isOpen={open}
        onClear={onClear}
      />
    </PopoverTrigger>
    <PopoverContent
      align="start"
      sideOffset={4}
      className="w-auto rounded-md border border-border bg-background p-0 shadow-sm"
      {...contentProps}
    >
      {children}
    </PopoverContent>
  </Popover>
);

export default FilterChipPopover;
