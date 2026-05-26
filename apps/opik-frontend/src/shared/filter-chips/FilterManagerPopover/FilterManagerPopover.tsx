import React from "react";
import { Pin, PinOff } from "lucide-react";
import groupBy from "lodash/groupBy";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { isSingleSelectApplied } from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChip.logic";
import { isPseudoSearchApplied } from "@/shared/filter-chips/chips/PseudoSearchChip/PseudoSearchChip.logic";
import { isBooleanApplied } from "@/shared/filter-chips/chips/BooleanChip/BooleanChip.logic";
import { isNumericApplied } from "@/shared/filter-chips/chips/NumericChip/NumericChip.logic";
import { isTimeApplied } from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import { isQueryBuilderApplied } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChip.logic";
import {
  BooleanChipValue,
  ChipDefinition,
  ChipValue,
  ChipValueMap,
  NumericChipValue,
  PseudoSearchChipValue,
  QueryBuilderChipValue,
  SingleSelectChipValue,
  TimeChipValue,
} from "@/shared/filter-chips/types";

interface FilterManagerPopoverProps {
  trigger: React.ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  chipsPinned: ChipDefinition[];
  chipsUnpinned: ChipDefinition[];
  values: ChipValueMap;
  onApplyValue: (id: string, value: ChipValue) => void;
  onPinChip: (id: string) => void;
  onUnpinChip: (id: string) => void;
  onRequestOpenChip: (id: string) => void;
}

export const chipHasAppliedValue = (
  definition: ChipDefinition,
  value: ChipValue | undefined,
): boolean => {
  switch (definition.kind) {
    case "single-select":
      return isSingleSelectApplied(value as SingleSelectChipValue | undefined);
    case "pseudo-search":
      return isPseudoSearchApplied(value as PseudoSearchChipValue | undefined);
    case "boolean":
      return isBooleanApplied(value as BooleanChipValue | undefined);
    case "numeric":
      return isNumericApplied(value as NumericChipValue | undefined);
    case "time":
      return isTimeApplied(value as TimeChipValue | undefined);
    case "query-builder":
      return isQueryBuilderApplied(value as QueryBuilderChipValue | undefined);
  }
};

const FilterManagerPopover: React.FC<FilterManagerPopoverProps> = ({
  trigger,
  open,
  onOpenChange,
  chipsPinned,
  chipsUnpinned,
  values,
  onApplyValue,
  onPinChip,
  onUnpinChip,
  onRequestOpenChip,
}) => {
  const handleSelectUnpinned = (def: ChipDefinition) => {
    onOpenChange(false);
    if (def.kind === "boolean") {
      onApplyValue(def.id, { applied: true });
      return;
    }
    onPinChip(def.id);
    onRequestOpenChip(def.id);
  };

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={4}
        collisionPadding={16}
        onCloseAutoFocus={(e) => e.preventDefault()}
        className="max-h-[var(--radix-popover-content-available-height)] w-[242px] overflow-y-auto rounded-md border border-border bg-background p-1 shadow-sm"
      >
        {chipsPinned.length > 0 && (
          <>
            <SectionHeader>Pinned</SectionHeader>
            <ul className="flex flex-col">
              {chipsPinned.map((def) => {
                const isActive = chipHasAppliedValue(def, values[def.id]);
                return (
                  <PinnedRow
                    key={def.id}
                    label={def.label}
                    isActive={isActive}
                    onUnpin={() => onUnpinChip(def.id)}
                  />
                );
              })}
            </ul>
            {chipsUnpinned.length > 0 && (
              <div className="flex w-full items-center justify-center py-1">
                <div className="h-px w-[226px] bg-border" />
              </div>
            )}
          </>
        )}

        {chipsUnpinned.length > 0 && (
          <>
            <SectionHeader>All filters</SectionHeader>
            {groupUnpinnedChips(chipsUnpinned).map((group, idx) => (
              <React.Fragment key={group.name ?? `__none_${idx}`}>
                {group.name && <SubgroupHeader>{group.name}</SubgroupHeader>}
                <ul className="flex flex-col">
                  {group.chips.map((def) => (
                    <UnpinnedRow
                      key={def.id}
                      label={def.label}
                      onSelect={() => handleSelectUnpinned(def)}
                    />
                  ))}
                </ul>
              </React.Fragment>
            ))}
          </>
        )}
      </PopoverContent>
    </Popover>
  );
};

const SectionHeader: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <div className="comet-body-s-accented flex h-10 min-w-[200px] items-center px-4 text-light-slate">
    {children}
  </div>
);

const SubgroupHeader: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <div className="comet-body-xs-accented flex h-10 min-w-[200px] items-center px-4 text-light-slate">
    {children}
  </div>
);

const groupUnpinnedChips = (chips: ChipDefinition[]) =>
  Object.entries(groupBy(chips, (def) => def.group ?? "")).map(
    ([name, items]) => ({ name, chips: items }),
  );

interface PinnedRowProps {
  label: string;
  isActive: boolean;
  onUnpin: () => void;
}

const PinnedRow: React.FC<PinnedRowProps> = ({ label, isActive, onUnpin }) => {
  const tooltip = isActive ? "Unpin and clear filter" : "Unpin";
  return (
    <li>
      <TooltipWrapper content={tooltip}>
        <button
          type="button"
          aria-label={tooltip}
          onClick={onUnpin}
          className={cn(
            "group flex h-10 w-full min-w-[200px] items-center justify-between gap-2 rounded-[4px] px-4 text-left outline-none",
            "hover:bg-primary-foreground focus-visible:bg-primary-foreground",
          )}
        >
          <span className="truncate text-sm text-foreground">
            {label}
            {isActive && " (active)"}
          </span>
          <span className="flex size-4 shrink-0 items-center justify-center text-light-slate">
            <Pin className="size-4 group-hover:hidden" />
            <PinOff className="hidden size-4 group-hover:block" />
          </span>
        </button>
      </TooltipWrapper>
    </li>
  );
};

interface UnpinnedRowProps {
  label: string;
  onSelect: () => void;
}

const UnpinnedRow: React.FC<UnpinnedRowProps> = ({ label, onSelect }) => (
  <li>
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        "flex h-10 w-full min-w-[200px] items-center gap-2 rounded-[4px] px-4 text-left outline-none",
        "hover:bg-primary-foreground focus-visible:bg-primary-foreground",
      )}
    >
      <span className="truncate text-sm text-foreground">{label}</span>
    </button>
  </li>
);

export default FilterManagerPopover;
