import React from "react";
import FilterChipPopover from "@/shared/filter-chips/chips/FilterChipPopover";
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
    <FilterChipPopover
      label={definition.label}
      valueSummary={summary?.display}
      valueSummaryFull={summary?.tooltip}
      open={open}
      onOpenChange={onOpenChange}
      onClear={onClear}
      contentProps={{
        onOpenAutoFocus: (event) => {
          if (value?.rows && value.rows.length > 0) event.preventDefault();
        },
      }}
    >
      <QueryBuilderChipPopoverContent
        definition={definition}
        value={value}
        onApply={onApply}
        onClear={onClear}
      />
    </FilterChipPopover>
  );
};

export default QueryBuilderChip;
