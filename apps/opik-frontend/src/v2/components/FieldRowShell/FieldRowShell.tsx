import React from "react";
import { MoveRight, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FieldRowShellProps = {
  name: React.ReactNode;
  value: React.ReactNode;
  note?: React.ReactNode;
  message?: React.ReactNode;
  removeLabel: string;
  removeTooltip?: string;
  removeIcon?: React.ReactNode;
  removeVariant?: "divided" | "inline";
  removeDisabledReason?: string;
  onRemove?: () => void;
  className?: string;
  "data-testid"?: string;
};

// Shared "name → value" row used wherever dataset fields are edited, so the
// record panel and the Add-to-dataset field mapping look the same.
const FieldRowShell: React.FunctionComponent<FieldRowShellProps> = ({
  name,
  value,
  note,
  message,
  removeLabel,
  removeTooltip,
  removeIcon = <Trash />,
  removeVariant = "divided",
  removeDisabledReason,
  onRemove,
  className,
  "data-testid": testId,
}) => (
  <div data-testid={testId} className={className}>
    <div className="overflow-hidden rounded-md border border-border">
      <div className="flex">
        <div className="flex min-w-0 flex-1 items-start gap-1 py-1.5 pl-2 pr-1">
          <div className="w-[165px] min-w-0 max-w-[40%] shrink-0">{name}</div>
          <div className="flex size-6 shrink-0 items-center justify-center text-light-slate">
            <MoveRight className="size-3" />
          </div>
          <div className="min-w-0 flex-1">{value}</div>
          {removeVariant === "inline" &&
            (onRemove ? (
              <TooltipWrapper content={removeTooltip}>
                <Button
                  variant="minimal"
                  size="icon-2xs"
                  aria-label={removeLabel}
                  onClick={onRemove}
                  className="shrink-0"
                >
                  {removeIcon}
                </Button>
              </TooltipWrapper>
            ) : (
              removeDisabledReason && (
                <TooltipWrapper content={removeDisabledReason}>
                  {/* Disabled buttons swallow hover, so the wrapper carries the tooltip. */}
                  <span className="flex shrink-0" tabIndex={0}>
                    <Button
                      variant="minimal"
                      size="icon-2xs"
                      aria-label={removeLabel}
                      disabled
                      className="disabled:opacity-50"
                    >
                      {removeIcon}
                    </Button>
                  </span>
                </TooltipWrapper>
              )
            ))}
        </div>
        {onRemove && removeVariant === "divided" && (
          <TooltipWrapper content={removeTooltip}>
            <Button
              variant="minimal"
              size="icon-2xs"
              aria-label={removeLabel}
              onClick={onRemove}
              className="h-auto shrink-0 self-stretch rounded-none border-l border-border hover:bg-primary-foreground"
            >
              {removeIcon}
            </Button>
          </TooltipWrapper>
        )}
      </div>
      {note && <div className="px-2 pb-1.5 pt-2.5">{note}</div>}
    </div>
    {message}
  </div>
);

export default FieldRowShell;
