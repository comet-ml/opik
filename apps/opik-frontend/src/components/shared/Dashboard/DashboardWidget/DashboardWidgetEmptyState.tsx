import React from "react";
import { Inbox } from "lucide-react";
import { cn } from "@/lib/utils";

type DashboardWidgetEmptyStateProps = {
  title?: string;
  message?: string;
  icon?: React.ReactNode;
  className?: string;
};

const DashboardWidgetEmptyState: React.FC<DashboardWidgetEmptyStateProps> = ({
  title = "No data",
  message = "There is no data to display for this widget",
  icon,
  className,
}) => {
  return (
    <div
      className={cn(
        "flex h-full flex-col items-center justify-center p-8 text-center",
        className,
      )}
    >
      <div className="mb-3 text-muted-slate">
        {icon || <Inbox className="size-12" />}
      </div>
      <h3 className="comet-body-s-accented mb-1 text-foreground">{title}</h3>
      <p className="comet-body-s text-muted-slate">{message}</p>
    </div>
  );
};

export default DashboardWidgetEmptyState;
