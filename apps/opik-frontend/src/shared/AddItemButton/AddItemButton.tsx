import React from "react";
import { Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import DisabledTooltip from "@/shared/DisabledTooltip/DisabledTooltip";

type AddItemButtonProps = {
  label: string;
  onClick: () => void;
  variant?: "dashed" | "inline";
  disabled?: boolean;
  disabledMessage?: string;
};

const AddItemButton: React.FC<AddItemButtonProps> = ({
  label,
  onClick,
  variant = "dashed",
  disabled = false,
  disabledMessage = "",
}) => {
  const isDashed = variant === "dashed";

  return (
    <DisabledTooltip
      disabled={disabled}
      message={disabledMessage}
      className={cn(isDashed ? "w-full" : "self-start")}
    >
      <Button
        type="button"
        variant="ghost"
        size="xs"
        className={cn(
          "text-foreground hover:text-primary-hover",
          isDashed
            ? "h-8 w-full rounded-md border border-dashed border-border bg-soft-background"
            : "pl-1",
        )}
        onClick={onClick}
        disabled={disabled}
      >
        <Plus className="mr-0.5 size-3" />
        {label}
      </Button>
    </DisabledTooltip>
  );
};

export default AddItemButton;
