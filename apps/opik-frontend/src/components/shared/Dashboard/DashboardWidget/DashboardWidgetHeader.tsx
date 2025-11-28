import React, { useState } from "react";
import { cn } from "@/lib/utils";

type DashboardWidgetHeaderProps = {
  title: string;
  subtitle?: string;
  actions?: React.ReactElement<{ onOpenChange?: (open: boolean) => void }>;
  dragHandle?: React.ReactNode;
  className?: string;
  preview?: boolean;
};

const DashboardWidgetHeader: React.FC<DashboardWidgetHeaderProps> = ({
  title,
  subtitle,
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
        "relative flex flex-col gap-0.5 rounded px-2 pb-0.5 pt-1",
        className,
      )}
    >
      <div className="flex items-center gap-2">
        <div
          className={cn(
            "flex min-w-0 flex-1 flex-col gap-0.5 transition-[padding-right]",
            actionsWithHandler && (menuOpen ? "pr-14" : "group-hover:pr-14"),
          )}
        >
          <div className="truncate text-xs font-medium text-foreground">
            {title}
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
              "absolute right-2 top-1 flex items-center gap-2 transition-opacity",
              menuOpen ? "opacity-100" : "opacity-0 group-hover:opacity-100",
            )}
          >
            {actionsWithHandler}
            {showDragHandle && (
              <>
                <div className="h-4 w-px bg-muted" />
                {dragHandle}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default DashboardWidgetHeader;
