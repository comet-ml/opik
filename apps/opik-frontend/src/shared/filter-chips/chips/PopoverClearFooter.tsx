import React from "react";
import { Button } from "@/ui/button";

interface PopoverClearFooterProps {
  onClear: () => void;
  disabled?: boolean;
}

export const PopoverClearFooter: React.FC<PopoverClearFooterProps> = ({
  onClear,
  disabled = false,
}) => (
  <Button
    variant="ghost"
    size="2xs"
    className="self-start px-0 text-foreground hover:text-primary"
    onClick={onClear}
    disabled={disabled}
  >
    Clear
  </Button>
);
