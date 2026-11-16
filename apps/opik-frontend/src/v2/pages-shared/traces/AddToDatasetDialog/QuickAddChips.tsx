import React from "react";

import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import LoadableSelectBox from "@/v2/components/LoadableSelectBox/LoadableSelectBox";
import { EnrichmentOptions } from "./useAddToDatasetForm";
import { QuickAddChip } from "./fieldMappingTypes";

const MAX_DATASET_CHIPS = 6;

const DATASET_CHIP_TOOLTIP =
  "Already used in this dataset — map a field to fill the same column.";

type QuickAddChipsProps = {
  managedChips: QuickAddChip[];
  datasetChips: string[];
  onAddManaged: (option: keyof EnrichmentOptions) => void;
  onAddDatasetColumn: (column: string) => void;
};

const QuickAddChips: React.FunctionComponent<QuickAddChipsProps> = ({
  managedChips,
  datasetChips,
  onAddManaged,
  onAddDatasetColumn,
}) => {
  if (managedChips.length === 0 && datasetChips.length === 0) return null;

  const visibleColumns = datasetChips.slice(0, MAX_DATASET_CHIPS);
  const overflowColumns = datasetChips.slice(MAX_DATASET_CHIPS);

  return (
    <div
      data-testid="quick-add-chips"
      className="flex flex-wrap items-center gap-2"
    >
      <span className="comet-body-xs text-muted-slate">Quick add:</span>

      {managedChips.map((chip) => (
        <button
          key={chip.option}
          type="button"
          className="flex"
          onClick={() => onAddManaged(chip.option)}
        >
          <Tag variant="gray" className="text-foreground">
            {chip.label}
          </Tag>
        </button>
      ))}

      {visibleColumns.map((column) => (
        <TooltipWrapper key={column} content={DATASET_CHIP_TOOLTIP}>
          <button
            type="button"
            className="flex"
            onClick={() => onAddDatasetColumn(column)}
          >
            <Tag variant="blue">{column}</Tag>
          </button>
        </TooltipWrapper>
      ))}

      {overflowColumns.length > 0 && (
        <LoadableSelectBox
          options={overflowColumns.map((column) => ({
            value: column,
            label: column,
          }))}
          onChange={onAddDatasetColumn}
          searchPlaceholder="Search columns"
          trigger={
            <button type="button" className="flex">
              <Tag variant="blue">+{overflowColumns.length} more</Tag>
            </button>
          }
        />
      )}
    </div>
  );
};

export default QuickAddChips;
