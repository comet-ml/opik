import React from "react";
import { CircleX } from "lucide-react";
import { cn } from "@/lib/utils";
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

  const handleClick = () => {
    if (applied) onClear();
    else onApply({ applied: true });
  };

  return (
    <button
      type="button"
      aria-pressed={applied}
      onClick={handleClick}
      className={cn(
        "comet-body-xs-accented flex h-6 shrink-0 items-center gap-1 overflow-hidden whitespace-nowrap rounded-[20px] border border-solid px-2 py-0.5 outline-none transition-colors",
        applied
          ? "border-secondary bg-primary-100/50 text-primary-active hover:bg-primary-100"
          : "border-border bg-soft-background text-muted-slate hover:border-secondary hover:bg-primary-100 hover:text-primary-hover",
        "focus-visible:ring-2 focus-visible:ring-primary-active/40",
      )}
    >
      <span>{definition.label}</span>
      {applied && <CircleX className="size-3 shrink-0" />}
    </button>
  );
};

export default BooleanChip;
