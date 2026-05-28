import React, { useState } from "react";
import { Pin, PinOff } from "lucide-react";
import groupBy from "lodash/groupBy";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Separator } from "@/ui/separator";
import { cn } from "@/lib/utils";
import SearchInput from "@/shared/SearchInput/SearchInput";
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
  const [searchText, setSearchText] = useState("");

  const handleOpenChange = (next: boolean) => {
    if (!next) setSearchText("");
    onOpenChange(next);
  };

  const handleSelectUnpinned = (def: ChipDefinition) => {
    if (def.kind === "boolean") {
      onApplyValue(def.id, { applied: true });
      return;
    }
    onPinChip(def.id);
    // Defer until the DropdownMenu has closed so its focus-return doesn't
    // race with the chip's popover trying to open.
    requestAnimationFrame(() => onRequestOpenChip(def.id));
  };

  const query = searchText.trim().toLowerCase();
  const matches = (def: ChipDefinition) =>
    query === "" || def.label.toLowerCase().includes(query);

  const visiblePinned = chipsPinned.filter(matches);
  const visibleUnpinned = chipsUnpinned.filter(matches);
  const hasResults = visiblePinned.length > 0 || visibleUnpinned.length > 0;

  return (
    <DropdownMenu open={open} onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        sideOffset={4}
        collisionPadding={16}
        onCloseAutoFocus={(e) => e.preventDefault()}
        className="flex max-h-[var(--radix-dropdown-menu-content-available-height)] w-[242px] flex-col p-1 shadow-sm"
      >
        <div className="shrink-0" onKeyDown={(e) => e.stopPropagation()}>
          <SearchInput
            searchText={searchText}
            setSearchText={setSearchText}
            placeholder="Search"
            dimension="sm"
            variant="ghost"
          />
          <Separator className="my-1" />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {visiblePinned.length > 0 && (
            <>
              <DropdownMenuLabel className="text-light-slate">
                Pinned filters
              </DropdownMenuLabel>
              {visiblePinned.map((def) => {
                const isActive = chipHasAppliedValue(def, values[def.id]);
                const tooltip = isActive ? "Unpin and clear filter" : "Unpin";
                return (
                  <TooltipWrapper key={def.id} content={tooltip}>
                    <DropdownMenuItem
                      size="sm"
                      onSelect={(e) => {
                        e.preventDefault();
                        onUnpinChip(def.id);
                      }}
                      aria-label={tooltip}
                      className="group flex justify-between gap-2 px-4 text-foreground"
                    >
                      <span className="truncate text-foreground">
                        {def.label}
                        {isActive && " (active)"}
                      </span>
                      <span className="flex size-4 shrink-0 items-center justify-center text-light-slate">
                        <Pin className="size-4 group-hover:hidden" />
                        <PinOff className="hidden size-4 group-hover:block" />
                      </span>
                    </DropdownMenuItem>
                  </TooltipWrapper>
                );
              })}
              {visibleUnpinned.length > 0 && (
                <div className="px-1">
                  <Separator className="my-1" />
                </div>
              )}
            </>
          )}

          {visibleUnpinned.length > 0 && (
            <>
              <DropdownMenuLabel className="text-light-slate">
                Available filters
              </DropdownMenuLabel>
              {groupUnpinnedChips(visibleUnpinned).map((group, idx) => (
                <React.Fragment key={group.name ?? `__none_${idx}`}>
                  {group.name && (
                    <DropdownMenuLabel
                      className={cn(
                        "comet-body-xs-accented text-light-slate",
                        "min-h-10 py-2.5",
                      )}
                    >
                      {group.name}
                    </DropdownMenuLabel>
                  )}
                  {group.chips.map((def) => (
                    <DropdownMenuItem
                      key={def.id}
                      size="sm"
                      onSelect={() => handleSelectUnpinned(def)}
                      className="px-4 text-foreground"
                    >
                      {def.label}
                    </DropdownMenuItem>
                  ))}
                </React.Fragment>
              ))}
            </>
          )}

          {!hasResults && (
            <div className="comet-body-s flex h-32 w-full items-center justify-center text-muted-slate">
              No search results
            </div>
          )}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

const groupUnpinnedChips = (chips: ChipDefinition[]) =>
  Object.entries(groupBy(chips, (def) => def.group ?? "")).map(
    ([name, items]) => ({ name, chips: items }),
  );

export default FilterManagerPopover;
