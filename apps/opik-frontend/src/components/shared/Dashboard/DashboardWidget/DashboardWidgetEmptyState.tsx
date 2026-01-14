import React from "react";
import { Inbox, Pencil } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

type DashboardWidgetEmptyStateProps = {
  title?: string;
  message?: string;
  icon?: React.ReactNode;
  className?: string;
  onAction?: () => void;
  actionLabel?: string;
};

const DashboardWidgetEmptyState: React.FunctionComponent<
  DashboardWidgetEmptyStateProps
> = ({
  title = "No data",
  message = "There is no data to display for this widget",
  icon,
  className,
  onAction,
  actionLabel = "Configure",
}) => {
  return (
    <div
      className={cn(
        "flex h-full flex-col overflow-y-auto overflow-x-hidden py-2 px-4",
        className,
      )}
    >
      <div className="m-auto flex w-full flex-col items-center text-center">
        <div className="mb-3 text-muted-slate">
          {icon || <Inbox className="size-4" />}
        </div>
        <h3 className="comet-body-s-accented mb-1 text-foreground">{title}</h3>
        <p className="comet-body-s w-full break-words pb-1 text-muted-slate">
          {message}
        </p>
        {onAction && (
          <Button
            variant="link"
            size="sm"
            onClick={(e) => {
              e.stopPropagation();
              onAction();
            }}
            className="gap-1"
          >
            <Pencil className="size-3.5" />
            {actionLabel}
          </Button>
        )}
      </div>
    </div>
  );
};

export default DashboardWidgetEmptyState;
