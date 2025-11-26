import React, { useState } from "react";
import { cn } from "@/lib/utils";

type DashboardWidgetHeaderProps = {
  title: string;
  subtitle?: string;
  actions?: React.ReactElement<{ onOpenChange?: (open: boolean) => void }>;
  dragHandle?: React.ReactNode;
  className?: string;
};

const DashboardWidgetHeader: React.FC<DashboardWidgetHeaderProps> = ({
  title,
  subtitle,
  actions,
  dragHandle,
  className,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);

  const actionsWithHandler = actions
    ? React.cloneElement(actions, { onOpenChange: setMenuOpen })
    : null;

  return (
    <div
      className={cn(
        "flex flex-col gap-0.5 rounded px-2 pb-0.5 pt-1",
        className,
      )}
    >
      <div className="flex items-center gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
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
              "flex items-center gap-2 transition-opacity",
              menuOpen ? "opacity-100" : "opacity-0 group-hover:opacity-100",
            )}
          >
            {actionsWithHandler}
            {dragHandle && (
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
