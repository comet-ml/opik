import React from "react";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { PopoverClearFooter } from "@/shared/filter-chips/chips/PopoverClearFooter";
import { isPseudoSearchApplied } from "@/shared/filter-chips/chips/PseudoSearchChip/PseudoSearchChip.logic";
import { trimValue } from "@/shared/filter-chips/lib/helpers";
import {
  PseudoSearchChipDefinition,
  PseudoSearchChipValue,
} from "@/shared/filter-chips/types";

interface PseudoSearchChipPopoverContentProps {
  definition: PseudoSearchChipDefinition;
  value: PseudoSearchChipValue | undefined;
  onApply: (value: PseudoSearchChipValue) => void;
  onClear: () => void;
  onCommit?: () => void;
}

const PseudoSearchChipPopoverContent: React.FC<
  PseudoSearchChipPopoverContentProps
> = ({ definition, value, onApply, onClear, onCommit }) => {
  const placeholder = definition.placeholder ?? `Search by ${definition.label}`;

  const handleChange = (
    raw: string | number | readonly string[] | undefined,
  ) => {
    const trimmed = trimValue(raw);
    if (trimmed === "") onClear();
    else onApply({ value: trimmed });
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      const trimmed = trimValue(event.currentTarget.value);
      if (trimmed === "") onClear();
      else onApply({ value: trimmed });
      onCommit?.();
    }
  };

  return (
    <div className="flex w-[291px] flex-col gap-4 p-3">
      <DebounceInput
        autoFocus
        dimension="sm"
        value={value?.value ?? ""}
        onValueChange={handleChange}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
      />

      <PopoverClearFooter
        onClear={onClear}
        disabled={!isPseudoSearchApplied(value)}
      />
    </div>
  );
};

export default PseudoSearchChipPopoverContent;
