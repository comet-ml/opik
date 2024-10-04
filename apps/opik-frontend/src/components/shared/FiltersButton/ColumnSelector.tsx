import React, { useCallback, useMemo } from "react";
import { ColumnData, DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import find from "lodash/find";

export type ColumnSelectorProps<TColumnData> = {
  field: string;
  columns: ColumnData<TColumnData>[];
  onSelect: (column: ColumnData<TColumnData>) => void;
};

const ColumnSelector = <TColumnData,>({
  field,
  columns,
  onSelect,
}: ColumnSelectorProps<TColumnData>) => {
  const options = useMemo(() => {
    return columns.map<DropdownOption<string>>((c) => ({
      value: c.id,
      label: c.label,
    }));
  }, [columns]);

  const handleChange = useCallback(
    (id: string) => {
      onSelect(find(columns, (c) => c.id === id) ?? columns[0]);
    },
    [columns, onSelect],
  );

  return (
    <SelectBox
      value={field}
      options={options}
      placeholder="Column"
      onChange={handleChange}
      testId="filter-column"
    />
  );
};

export default ColumnSelector;
