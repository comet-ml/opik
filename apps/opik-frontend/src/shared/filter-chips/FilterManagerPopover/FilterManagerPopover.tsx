import React, { useRef, useState } from "react";
import { Pin, PinOff } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Separator } from "@/ui/separator";
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
  const pendingChipIdRef = useRef<string | null>(null);

  const handleOpenChange = (next: boolean) => {
    if (!next) setSearchText("");
    onOpenChange(next);
  };

  const handleSelectUnpinned = (def: ChipDefinition) => {
    onPinChip(def.id);
    if (def.kind === "boolean") {
      onApplyValue(def.id, { applied: true });
      return;
    }
    // Open the chip popover only after Radix has finished tearing down the
    // dropdown — see onCloseAutoFocus below. Avoids the stray outside-click
    // event that otherwise races with the mounting chip popover.
    pendingChipIdRef.current = def.id;
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
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          if (pendingChipIdRef.current) {
            onRequestOpenChip(pendingChipIdRef.current);
            pendingChipIdRef.current = null;
          }
        }}
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
                Pinned
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
                      <span className="flex size-4 shrink-0 items-center justify-center text-light-slate transition-colors group-hover:text-foreground">
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

          {visibleUnpinned.map((def) => (
            <DropdownMenuItem
              key={def.id}
              size="sm"
              onSelect={() => handleSelectUnpinned(def)}
              className="px-4 text-foreground"
            >
              {def.label}
            </DropdownMenuItem>
          ))}

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

export default FilterManagerPopover;
