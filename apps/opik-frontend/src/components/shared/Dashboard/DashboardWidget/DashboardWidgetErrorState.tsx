import React from "react";
import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type DashboardWidgetErrorStateProps = {
  title?: string;
  message?: string;
  error?: Error | string;
  onRetry?: () => void;
  className?: string;
};

const DashboardWidgetErrorState: React.FunctionComponent<
  DashboardWidgetErrorStateProps
> = ({
  title = "Failed to load data",
  message = "An error occurred while loading the widget data",
  error,
  onRetry,
  className,
}) => {
  const errorMessage = typeof error === "string" ? error : error?.message;

  return (
    <div
      className={cn(
        "flex h-full flex-col items-center justify-center p-8 text-center",
        className,
      )}
    >
      <div className="mb-3 text-destructive">
        <AlertCircle className="size-12" />
      </div>
      <h3 className="comet-body-s-accented mb-1 text-foreground">{title}</h3>
      <p className="comet-body-s mb-2 text-muted-slate">{message}</p>
      {errorMessage && (
        <p className="comet-body-xs mb-4 max-w-md text-muted-slate">
          {errorMessage}
        </p>
      )}
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
};

export default DashboardWidgetErrorState;
