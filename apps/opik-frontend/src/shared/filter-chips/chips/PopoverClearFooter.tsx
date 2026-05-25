import React from "react";
import { Button } from "@/ui/button";

interface PopoverClearFooterProps {
  onClear: () => void;
}

export const PopoverClearFooter: React.FC<PopoverClearFooterProps> = ({
  onClear,
}) => (
  <div className="border-t border-border pt-2">
    <Button
      variant="ghost"
      size="sm"
      className="comet-body-xs h-6 px-2 font-normal text-muted-gray"
      onClick={onClear}
    >
      Clear
    </Button>
  </div>
);
