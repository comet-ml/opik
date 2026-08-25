import React from "react";
import { AlertCircle } from "lucide-react";
import { Button } from "@/ui/button";

type DataTableLoadingErrorProps = {
  onRetry?: () => void;
};

const DataTableLoadingError: React.FC<DataTableLoadingErrorProps> = ({
  onRetry,
}) => {
  return (
    <div className="sticky left-0 flex min-h-[50vh] w-[var(--scroll-body-client-width,100%)] items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-2">
        <AlertCircle className="mb-1 size-8 text-muted-slate" />
        <h3 className="comet-body">Could not load results</h3>
        <p className="comet-body-s max-w-[570px] text-center text-muted-slate">
          Something went wrong while loading this list. Your filters may not be
          supported, or the request failed.
        </p>
        {onRetry && (
          // Called through a wrapper, not passed as `onClick={onRetry}`: every
          // call site hands this React Query's `refetch`, which takes
          // `RefetchOptions` — passing it directly forwards the click event
          // into that slot.
          <Button
            variant="outline"
            size="sm"
            className="mt-2"
            onClick={() => onRetry()}
          >
            Retry
          </Button>
        )}
      </div>
    </div>
  );
};

export default DataTableLoadingError;
