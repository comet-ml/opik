import React, { useCallback } from "react";
import { DropdownOption, ROW_HEIGHT } from "@/types/shared";
import { Check, Rows3 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";

type DataTableRowHeightSelectorProps = {
  type: string;
  setType: (type: ROW_HEIGHT) => void;
};

const OPTIONS: DropdownOption<ROW_HEIGHT>[] = [
  { value: ROW_HEIGHT.small, label: "Compact" },
  { value: ROW_HEIGHT.medium, label: "Medium" },
  { value: ROW_HEIGHT.large, label: "Detailed" },
];

const DataTableRowHeightSelector: React.FunctionComponent<
  DataTableRowHeightSelectorProps
> = ({ type, setType }) => {
  const handleSelect = useCallback(
    (value: ROW_HEIGHT) => {
      setType(value);
    },
    [setType],
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">
          <Rows3 className="mr-1.5 size-3.5" />
          Rows
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {OPTIONS.map(({ value, label }) => (
          <DropdownMenuItem key={value} onClick={() => handleSelect(value)}>
            <div className="relative flex w-full items-center pl-4">
              {type === value && <Check className="absolute -left-2 size-4" />}
              <span>{label}</span>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DataTableRowHeightSelector;
