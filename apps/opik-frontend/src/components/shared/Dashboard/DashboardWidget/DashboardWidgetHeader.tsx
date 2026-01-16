import React, { useState } from "react";
import { TriangleAlert, Info } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DashboardWidgetHeaderProps = {
  title: string;
  subtitle?: string;
  warningMessage?: string;
  infoMessage?: string;
  actions?: React.ReactElement<{ onOpenChange?: (open: boolean) => void }>;
  dragHandle?: React.ReactNode;
  className?: string;
  preview?: boolean;
};

const DashboardWidgetHeader: React.FunctionComponent<
  DashboardWidgetHeaderProps
> = ({
  title,
  subtitle,
  warningMessage,
  infoMessage,
  actions,
  dragHandle,
  className,
  preview = false,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);

  const actionsWithHandler =
    actions && !preview
      ? React.cloneElement(actions, { onOpenChange: setMenuOpen })
      : null;

  const showDragHandle = dragHandle && !preview;

  return (
    <div
      className={cn(
        "relative flex flex-col gap-0.5 rounded px-2 pb-0.5",
        showDragHandle ? "pt-1" : "pt-2",
        className,
      )}
    >
      {showDragHandle && (
        <div
          className={cn(
            "flex w-full items-center justify-center pb-0.5 transition-opacity",
            menuOpen
              ? "opacity-100"
              : "opacity-0 group-hover/widget:opacity-100",
          )}
        >
          {dragHandle}
        </div>
      )}
      <div className="flex items-start gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <div className="flex items-center gap-1.5">
            {warningMessage && (
              <TooltipWrapper content={warningMessage}>
                <TriangleAlert className="size-3 shrink-0 text-amber-500" />
              </TooltipWrapper>
            )}
            <div className="truncate text-xs font-medium text-foreground">
              {title}
            </div>
            {infoMessage && (
              <TooltipWrapper content={infoMessage}>
                <Info className="size-3 shrink-0 text-light-slate" />
              </TooltipWrapper>
            )}
          </div>
          {subtitle && (
            <div className="truncate text-xs font-normal text-muted-slate">
              {subtitle}
            </div>
          )}
        </div>
        {actionsWithHandler && (
          <div
            className={cn(
              "flex shrink-0 items-center gap-2 transition-opacity",
              menuOpen
                ? "opacity-100"
                : "opacity-0 group-hover/widget:opacity-100",
            )}
          >
            {actionsWithHandler}
          </div>
        )}
      </div>
    </div>
  );
};

export default DashboardWidgetHeader;
