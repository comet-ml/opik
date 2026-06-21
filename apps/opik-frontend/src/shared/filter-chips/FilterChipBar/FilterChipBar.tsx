import React, { useCallback, useMemo } from "react";
import { ListFilter } from "lucide-react";
import { cn } from "@/lib/utils";
import FilterManagerPopover, {
  chipHasAppliedValue,
} from "@/shared/filter-chips/FilterManagerPopover/FilterManagerPopover";
import ChipShell from "@/shared/filter-chips/chips/BaseChip/ChipShell";
import SingleSelectChip from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChip";
import BooleanChip from "@/shared/filter-chips/chips/BooleanChip/BooleanChip";
import NumericChip from "@/shared/filter-chips/chips/NumericChip/NumericChip";
import TimeChip from "@/shared/filter-chips/chips/TimeChip/TimeChip";
import QueryBuilderChip from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChip";
import {
  BooleanChipValue,
  ChipDefinition,
  ChipValue,
  ChipValueMap,
  NumericChipValue,
  QueryBuilderChipValue,
  SingleSelectChipValue,
  TimeChipValue,
} from "@/shared/filter-chips/types";

export interface FilterChipBarProps {
  chipsPinned: ChipDefinition[];
  chipsUnpinned: ChipDefinition[];
  values: ChipValueMap;
  managerOpen: boolean;
  onManagerOpenChange: (open: boolean) => void;
  onApplyValue: (id: string, value: ChipValue) => void;
  onClearValue: (id: string, source?: "chip_x") => void;
  onPinChip: (id: string) => void;
  onUnpinChip: (id: string) => void;
  onClearAll: () => void;
  openChipId: string | null;
  onOpenChipIdChange: (id: string | null) => void;
  prefix?: React.ReactNode;
}

const FilterChipBar: React.FC<FilterChipBarProps> = ({
  chipsPinned,
  chipsUnpinned,
  values,
  managerOpen,
  onManagerOpenChange,
  onApplyValue,
  onClearValue,
  onPinChip,
  onUnpinChip,
  onClearAll,
  openChipId,
  onOpenChipIdChange,
  prefix,
}) => {
  const appliedCount = useMemo(() => {
    let count = 0;
    for (const def of chipsPinned) {
      if (chipHasAppliedValue(def, values[def.id])) count += 1;
    }
    for (const def of chipsUnpinned) {
      if (chipHasAppliedValue(def, values[def.id])) count += 1;
    }
    return count;
  }, [chipsPinned, chipsUnpinned, values]);

  const buildOnOpenChange = useCallback(
    (id: string) => (open: boolean) => onOpenChipIdChange(open ? id : null),
    [onOpenChipIdChange],
  );

  return (
    <div className="flex flex-wrap items-center gap-2">
      {prefix}
      {chipsPinned.map((def) => {
        const isOpen = def.id === openChipId;
        const handleOpenChange = buildOnOpenChange(def.id);
        switch (def.kind) {
          case "single-select":
            return (
              <SingleSelectChip
                key={def.id}
                definition={def}
                value={values[def.id] as SingleSelectChipValue}
                onApply={(value) => onApplyValue(def.id, value)}
                onClear={(source) => onClearValue(def.id, source)}
                open={isOpen}
                onOpenChange={handleOpenChange}
              />
            );
          case "boolean":
            return (
              <BooleanChip
                key={def.id}
                definition={def}
                value={values[def.id] as BooleanChipValue}
                onApply={(value) => onApplyValue(def.id, value)}
                onClear={(source) => onClearValue(def.id, source)}
              />
            );
          case "numeric":
            return (
              <NumericChip
                key={def.id}
                definition={def}
                value={values[def.id] as NumericChipValue}
                onApply={(value) => onApplyValue(def.id, value)}
                onClear={(source) => onClearValue(def.id, source)}
                open={isOpen}
                onOpenChange={handleOpenChange}
              />
            );
          case "time":
            return (
              <TimeChip
                key={def.id}
                definition={def}
                value={values[def.id] as TimeChipValue}
                onApply={(value) => onApplyValue(def.id, value)}
                onClear={(source) => onClearValue(def.id, source)}
                open={isOpen}
                onOpenChange={handleOpenChange}
              />
            );
          case "query-builder":
            return (
              <QueryBuilderChip
                key={def.id}
                definition={def}
                value={values[def.id] as QueryBuilderChipValue}
                onApply={(value) => onApplyValue(def.id, value)}
                onClear={(source) => onClearValue(def.id, source)}
                open={isOpen}
                onOpenChange={handleOpenChange}
              />
            );
        }
      })}

      <FilterManagerPopover
        open={managerOpen}
        onOpenChange={onManagerOpenChange}
        chipsPinned={chipsPinned}
        chipsUnpinned={chipsUnpinned}
        values={values}
        onApplyValue={onApplyValue}
        onPinChip={onPinChip}
        onUnpinChip={onUnpinChip}
        onRequestOpenChip={onOpenChipIdChange}
        trigger={
          <ChipShell isOpen={managerOpen} className="pr-2">
            <ListFilter className="size-3 shrink-0" />
            <span>All filters</span>
          </ChipShell>
        }
      />

      {appliedCount > 0 && (
        <button
          type="button"
          onClick={onClearAll}
          className={cn(
            "comet-body-xs flex h-6 shrink-0 items-center whitespace-nowrap rounded-[20px] px-2 py-0.5 text-foreground outline-none transition-colors",
            "hover:bg-primary-foreground",
            "focus-visible:ring-2 focus-visible:ring-primary-active/40",
          )}
        >
          Clear all ({appliedCount})
        </button>
      )}
    </div>
  );
};

export default FilterChipBar;
