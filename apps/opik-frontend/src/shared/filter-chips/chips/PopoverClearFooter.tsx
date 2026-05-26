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
  <div className="border-t border-border pt-2">
    <Button
      variant="ghost"
      size="2xs"
      className="px-0 text-foreground hover:text-primary"
      onClick={onClear}
      disabled={disabled}
    >
      Clear
    </Button>
  </div>
);
