import React, { useCallback } from "react";
import { ROW_HEIGHT } from "@/types/shared";
import { Rows2, Rows3, Rows4 } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

type DataTableRowHeightSelectorProps = {
  type: string;
  setType: (type: ROW_HEIGHT) => void;
};

const DataTableRowHeightSelector: React.FunctionComponent<
  DataTableRowHeightSelectorProps
> = ({ type, setType }) => {
  const handleTypeChange = useCallback(
    (value: string) => {
      value && setType(value as ROW_HEIGHT);
    },
    [setType],
  );

  return (
    <ToggleGroup
      type="single"
      value={type}
      onValueChange={handleTypeChange}
      size="icon-sm"
    >
      <ToggleGroupItem value={ROW_HEIGHT.small} aria-label="Small size">
        <Rows4 />
      </ToggleGroupItem>
      <ToggleGroupItem value={ROW_HEIGHT.medium} aria-label="Medium size">
        <Rows3 />
      </ToggleGroupItem>
      <ToggleGroupItem value={ROW_HEIGHT.large} aria-label="Large size">
        <Rows2 />
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default DataTableRowHeightSelector;
