import React from "react";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { PopoverClearFooter } from "@/shared/filter-chips/chips/PopoverClearFooter";
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
  const inputLabel = definition.inputLabel ?? `Search by ${definition.label}`;

  const handleChange = (
    raw: string | number | readonly string[] | undefined,
  ) => {
    const trimmed = String(raw ?? "").trim();
    if (trimmed === "") onClear();
    else onApply({ value: trimmed });
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      const trimmed = event.currentTarget.value.trim();
      if (trimmed === "") onClear();
      else onApply({ value: trimmed });
      onCommit?.();
    }
  };

  return (
    <div className="flex w-[291px] flex-col gap-4 p-3">
      <div className="flex flex-col gap-1">
        <label className="comet-body-s-accented px-0.5 pb-0.5 text-foreground">
          {inputLabel}
        </label>
        <DebounceInput
          autoFocus
          dimension="sm"
          value={value?.value ?? ""}
          onValueChange={handleChange}
          onKeyDown={handleKeyDown}
          placeholder={definition.placeholder ?? ""}
        />
      </div>

      <PopoverClearFooter onClear={onClear} />
    </div>
  );
};

export default PseudoSearchChipPopoverContent;
