import React from "react";
import { X } from "lucide-react";

import { Button, ButtonProps } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface SelectBoxClearWrapperProps {
  children: React.ReactElement;
  isClearable: boolean;
  onClear: () => void;
  disabled?: boolean;
  clearTooltip?: string;
  buttonSize?: ButtonProps["size"];
}

const SelectBoxClearWrapper: React.FC<SelectBoxClearWrapperProps> = ({
  children,
  isClearable,
  onClear,
  disabled = false,
  clearTooltip = "Clear selection",
  buttonSize = "default",
}) => {
  return (
    <div className="flex w-full">
      {children}

      {isClearable && (
        <TooltipWrapper content={clearTooltip}>
          <Button
            variant="outline"
            size={buttonSize}
            className="shrink-0 rounded-l-none border-l-0"
            onClick={onClear}
            disabled={disabled}
          >
            <X className="text-light-slate" />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default SelectBoxClearWrapper;
