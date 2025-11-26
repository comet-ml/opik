import React from "react";
import { cn } from "@/lib/utils";

type DashboardWidgetHeaderProps = {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
  className?: string;
};

const DashboardWidgetHeader: React.FC<DashboardWidgetHeaderProps> = ({
  title,
  subtitle,
  actions,
  className,
}) => {
  return (
    <div
      className={cn(
        "flex flex-col gap-0.5 rounded px-2 pb-0.5 pt-1",
        className,
      )}
    >
      <div className="flex items-center justify-between">
        <div className="flex-1">
          <div className="text-xs font-medium text-foreground">{title}</div>
        </div>
        {actions}
      </div>
      {subtitle && (
        <div className="text-xs font-normal text-muted-slate">{subtitle}</div>
      )}
    </div>
  );
};

export default DashboardWidgetHeader;
