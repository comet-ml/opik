import React from "react";
import ChipShell from "@/shared/filter-chips/chips/BaseChip/ChipShell";
import {
  BooleanChipDefinition,
  BooleanChipValue,
} from "@/shared/filter-chips/types";
import { isBooleanApplied } from "@/shared/filter-chips/chips/BooleanChip/BooleanChip.logic";

interface BooleanChipProps {
  definition: BooleanChipDefinition;
  value: BooleanChipValue | undefined;
  onApply: (value: BooleanChipValue) => void;
  onClear: () => void;
}

const BooleanChip: React.FC<BooleanChipProps> = ({
  definition,
  value,
  onApply,
  onClear,
}) => {
  const applied = isBooleanApplied(value);

  return (
    <ChipShell
      applied={applied}
      aria-pressed={applied}
      onClear={onClear}
      onClick={applied ? undefined : () => onApply({ applied: true })}
    >
      <span className="truncate">{definition.label}</span>
    </ChipShell>
  );
};

export default BooleanChip;
