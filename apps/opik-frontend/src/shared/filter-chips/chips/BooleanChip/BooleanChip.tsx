import React from "react";
import { CircleX } from "lucide-react";
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
      onClick={() => (applied ? onClear() : onApply({ applied: true }))}
    >
      <span className="truncate">{definition.label}</span>
      {applied && <CircleX className="size-3 shrink-0" />}
    </ChipShell>
  );
};

export default BooleanChip;
