import React from "react";
import { Button } from "@/ui/button";
import { PopoverClose } from "@/ui/popover";

interface PopoverClearFooterProps {
  onClear: () => void;
  disabled?: boolean;
}

export const PopoverClearFooter: React.FC<PopoverClearFooterProps> = ({
  onClear,
  disabled = false,
}) => (
  <PopoverClose asChild>
    <Button
      variant="ghost"
      size="2xs"
      className="self-start px-0 text-foreground hover:text-primary"
      onClick={onClear}
      disabled={disabled}
    >
      Clear
    </Button>
  </PopoverClose>
);
