import React from "react";
import { LockKeyhole } from "lucide-react";
import { cn } from "@/lib/utils";

interface DashboardWidgetDisabledStateProps {
  title?: string;
  message?: string;
  icon?: React.ReactNode;
}

const DashboardWidgetDisabledState: React.FunctionComponent<
  DashboardWidgetDisabledStateProps
> = ({
  title = "Widget not available",
  message = "You don't have permission to view this widget",
  icon,
}) => {
  return (
    <div className="flex size-full items-center justify-center">
      <div className="flex flex-col items-center gap-3 text-center">
        <div
          className={cn(
            "flex size-12 items-center justify-center rounded-full bg-muted",
          )}
        >
          {icon || <LockKeyhole className="size-6 text-muted-foreground" />}
        </div>
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium text-muted-foreground">{title}</p>
          {message && (
            <p className="text-xs text-muted-foreground/70">{message}</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default DashboardWidgetDisabledState;
