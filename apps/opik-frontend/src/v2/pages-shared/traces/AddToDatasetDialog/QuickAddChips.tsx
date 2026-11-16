import React from "react";

import { Tag } from "@/ui/tag";
import { EnrichmentOptions } from "./useAddToDatasetForm";
import { QuickAddChip } from "./fieldMappingTypes";

type QuickAddChipsProps = {
  chips: QuickAddChip[];
  onAdd: (option: keyof EnrichmentOptions) => void;
};

const QuickAddChips: React.FunctionComponent<QuickAddChipsProps> = ({
  chips,
  onAdd,
}) => {
  if (chips.length === 0) return null;

  return (
    <div
      data-testid="quick-add-chips"
      className="flex flex-wrap items-center gap-2"
    >
      <span className="comet-body-xs text-muted-slate">Quick add:</span>
      {chips.map((chip) => (
        <button
          key={chip.option}
          type="button"
          className="flex"
          onClick={() => onAdd(chip.option)}
        >
          <Tag variant="gray" className="text-foreground">
            {chip.label}
          </Tag>
        </button>
      ))}
    </div>
  );
};

export default QuickAddChips;
