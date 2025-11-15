import React from "react";
import { Progress } from "@/components/ui/progress";
import { CheckCircle, XCircle, Loader2 } from "lucide-react";

interface UploadProgressProps {
  fileName: string;
  percentage: number;
  isComplete?: boolean;
  hasError?: boolean;
  errorMessage?: string;
}

export const UploadProgress: React.FC<UploadProgressProps> = ({
  fileName,
  percentage,
  isComplete = false,
  hasError = false,
  errorMessage,
}) => {
  return (
    <div className="w-full space-y-2 rounded-md border border-border bg-muted/30 p-4">
      <div className="flex items-center justify-between">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {hasError ? (
            <XCircle className="size-4 shrink-0 text-destructive" />
          ) : isComplete ? (
            <CheckCircle className="size-4 shrink-0 text-success" />
          ) : (
            <Loader2 className="size-4 shrink-0 animate-spin text-primary" />
          )}
          <span className="comet-body-s truncate" title={fileName}>
            {fileName}
          </span>
        </div>
        <span className="comet-body-s-accented ml-2 text-muted-slate">
          {percentage}%
        </span>
      </div>
      <Progress value={percentage} className="h-2" />
      {hasError && errorMessage && (
        <p className="comet-body-xs text-destructive">{errorMessage}</p>
      )}
    </div>
  );
};
