import React from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SORT_DIRECTION } from "@/types/sorting";
import { DropdownOption } from "@/types/shared";

const OPTIONS: DropdownOption<SORT_DIRECTION>[] = [
  { label: "Ascending", value: SORT_DIRECTION.ASC },
  { label: "Descending", value: SORT_DIRECTION.DESC },
];

export type SortDirectionSelectorProps = {
  direction: SORT_DIRECTION;
  onSelect?: (order: SORT_DIRECTION) => void;
  disabled?: boolean;
};

const SortDirectionSelector: React.FC<SortDirectionSelectorProps> = ({
  direction,
  onSelect,
  disabled,
}) => {
  return (
    <SelectBox
      value={direction}
      options={OPTIONS}
      placeholder="Sort direction"
      onChange={onSelect as never}
      disabled={disabled}
    />
  );
};

export default SortDirectionSelector;
