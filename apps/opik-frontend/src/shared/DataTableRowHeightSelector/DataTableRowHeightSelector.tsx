import React, { useCallback } from "react";
import { DropdownOption, ROW_HEIGHT } from "@/types/shared";
import { Check, Rows3, UnfoldVertical } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button, ButtonProps } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DataTableRowHeightSelectorProps = {
  type: string;
  setType: (type: ROW_HEIGHT) => void;
  layout?: "icon" | "labeled";
  size?: ButtonProps["size"];
};

const OPTIONS: DropdownOption<ROW_HEIGHT>[] = [
  { value: ROW_HEIGHT.small, label: "Compact" },
  { value: ROW_HEIGHT.medium, label: "Medium" },
  { value: ROW_HEIGHT.large, label: "Detailed" },
];

const DataTableRowHeightSelector: React.FunctionComponent<
  DataTableRowHeightSelectorProps
> = ({ type, setType, layout = "icon", size = "icon-sm" }) => {
  const handleSelect = useCallback(
    (value: ROW_HEIGHT) => {
      setType(value);
    },
    [setType],
  );

  return (
    <DropdownMenu>
      <TooltipWrapper content="Row size">
        <DropdownMenuTrigger asChild>
          {layout === "labeled" ? (
            <Button variant="outline" size={size}>
              <UnfoldVertical className="mr-1.5 size-3.5" />
              Row size
            </Button>
          ) : (
            <Button
              variant="outline"
              size={size}
              className="focus-visible:border-primary focus-visible:ring-0"
            >
              <Rows3 />
            </Button>
          )}
        </DropdownMenuTrigger>
      </TooltipWrapper>
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
