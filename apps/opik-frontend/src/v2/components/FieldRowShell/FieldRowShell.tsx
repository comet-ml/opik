import React from "react";
import { MoveRight, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FieldRowShellProps = {
  name: React.ReactNode;
  value: React.ReactNode;
  message?: React.ReactNode;
  removeLabel: string;
  removeTooltip?: string;
  onRemove?: () => void;
  className?: string;
  "data-testid"?: string;
};

// Shared "name → value" row used wherever dataset fields are edited, so the
// record panel and the Add-to-dataset field mapping look the same.
const FieldRowShell: React.FunctionComponent<FieldRowShellProps> = ({
  name,
  value,
  message,
  removeLabel,
  removeTooltip,
  onRemove,
  className,
  "data-testid": testId,
}) => (
  <div data-testid={testId} className={className}>
    <div className="flex overflow-hidden rounded-md border border-border bg-background">
      <div className="flex min-w-0 flex-1 items-start gap-1 py-1.5 pl-2 pr-1">
        <div className="w-[165px] min-w-0 max-w-[40%] shrink-0">{name}</div>
        <div className="flex size-6 shrink-0 items-center justify-center text-light-slate">
          <MoveRight className="size-3" />
        </div>
        <div className="min-w-0 flex-1">{value}</div>
      </div>
      <TooltipWrapper content={onRemove ? removeTooltip : undefined}>
        <Button
          variant="minimal"
          size="icon-2xs"
          aria-label={removeLabel}
          disabled={!onRemove}
          onClick={onRemove}
          className="h-auto shrink-0 self-stretch rounded-none border-l border-border hover:bg-primary-foreground"
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
    {message}
  </div>
);

export default FieldRowShell;
