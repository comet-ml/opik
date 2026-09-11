import React from "react";
import { cn } from "@/lib/utils";

type DashboardWidgetActionsProps = {
  children: React.ReactNode;
  className?: string;
};

const DashboardWidgetActions: React.FunctionComponent<
  DashboardWidgetActionsProps
> = ({ children, className }) => {
  return (
    <div
      className={cn(
        "flex items-center gap-2 opacity-0 transition-opacity group-hover/widget:opacity-100",
        className,
      )}
    >
      {children}
    </div>
  );
};

export default DashboardWidgetActions;
