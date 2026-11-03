import React from "react";
import { Trash } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import DisabledTooltip from "@/shared/DisabledTooltip/DisabledTooltip";

type ConditionCardProps = {
  children: React.ReactNode;
  onRemove: () => void;
  canRemove?: boolean;
  removeDisabledMessage?: string;
  removeLabel?: string;
  errors?: (string | undefined)[];
  className?: string;
};

const ConditionCard: React.FC<ConditionCardProps> = ({
  children,
  onRemove,
  canRemove = true,
  removeDisabledMessage = "",
  removeLabel = "Remove condition",
  errors = [],
  className,
}) => {
  const messages = errors.filter(Boolean);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex min-h-11 items-stretch overflow-hidden rounded-md border border-border bg-background">
        <div
          className={cn(
            "flex min-w-0 flex-1 flex-wrap items-center gap-2 px-2 py-1.5",
            className,
          )}
        >
          {children}
        </div>
        <DisabledTooltip disabled={!canRemove} message={removeDisabledMessage}>
          <Button
            type="button"
            variant="minimal"
            size="icon-2xs"
            className="h-auto w-6 rounded-none border-l border-border opacity-50 hover:opacity-100"
            onClick={onRemove}
            disabled={!canRemove}
            aria-label={removeLabel}
          >
            <Trash />
          </Button>
        </DisabledTooltip>
      </div>
      {messages.length > 0 && (
        <div className="comet-body-s flex flex-wrap gap-x-2 px-2 text-destructive">
          {messages.map((message, index) => (
            <span key={index}>{message}</span>
          ))}
        </div>
      )}
    </div>
  );
};

export default ConditionCard;
