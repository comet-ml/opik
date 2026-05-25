import React from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import BaseChip from "@/shared/filter-chips/chips/BaseChip/BaseChip";
import QueryBuilderChipPopoverContent from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChipPopoverContent";
import { formatQueryBuilderSummary } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChip.logic";
import {
  QueryBuilderChipDefinition,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";

interface QueryBuilderChipProps {
  definition: QueryBuilderChipDefinition;
  value: QueryBuilderChipValue | undefined;
  onApply: (value: QueryBuilderChipValue) => void;
  onClear: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const QueryBuilderChip: React.FC<QueryBuilderChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
  open,
  onOpenChange,
}) => {
  const summary = formatQueryBuilderSummary(value, definition);

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <BaseChip
          label={definition.label}
          valueSummary={summary?.display}
          valueSummaryFull={summary?.tooltip}
          isOpen={open}
        />
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={4}
        onOpenAutoFocus={(event) => {
          if (value?.rows && value.rows.length > 0) event.preventDefault();
        }}
        className="w-auto rounded-md border border-border bg-background p-0 shadow-sm"
      >
        <QueryBuilderChipPopoverContent
          definition={definition}
          value={value}
          onApply={onApply}
          onClear={onClear}
        />
      </PopoverContent>
    </Popover>
  );
};

export default QueryBuilderChip;
