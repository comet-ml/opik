import React, { useEffect, useState, useRef } from "react";
import {
  ChevronDown,
  ChevronUp,
  Download,
  Loader2,
  X,
  CheckCircle2,
  AlertCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import {
  useActiveExportJobs,
  useHasActiveExportJobs,
  useIsPanelExpanded,
  useTogglePanelExpanded,
  useRemoveExportJob,
  useUpdateExportJob,
  useHydrateFromApi,
  useIsHydrated,
  ExportJobInfo,
} from "@/store/DatasetExportStore";
import useDatasetExportJob from "@/api/datasets/useDatasetExportJob";
import useDatasetExportJobs from "@/api/datasets/useDatasetExportJobs";
import useMarkExportJobViewedMutation from "@/api/datasets/useMarkExportJobViewedMutation";
import { DATASET_EXPORT_STATUS } from "@/types/datasets";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/components/ui/use-toast";

interface ExportJobItemProps {
  jobInfo: ExportJobInfo;
}

const ExportJobItem: React.FC<ExportJobItemProps> = ({ jobInfo }) => {
  const { job, datasetName } = jobInfo;
  const removeJob = useRemoveExportJob();
  const updateJob = useUpdateExportJob();
  const [isHovered, setIsHovered] = useState(false);
  const { toast } = useToast();
  const hasShownErrorToast = useRef(false);
  const { mutate: markAsViewed } = useMarkExportJobViewedMutation();

  // Poll for job status updates (every 5 seconds for PENDING/PROCESSING jobs)
  const { data: updatedJob } = useDatasetExportJob(
    {
      datasetId: job.dataset_id,
      jobId: job.id,
    },
    {
      enabled:
        job.status === DATASET_EXPORT_STATUS.PENDING ||
        job.status === DATASET_EXPORT_STATUS.PROCESSING,
    },
  );

  // Update store when job status changes
  useEffect(() => {
    if (updatedJob && updatedJob.status !== job.status) {
      updateJob(updatedJob);

      // Show toast when export fails and mark as viewed
      if (
        updatedJob.status === DATASET_EXPORT_STATUS.FAILED &&
        !hasShownErrorToast.current
      ) {
        hasShownErrorToast.current = true;
        toast({
          title: "Export failed",
          description:
            updatedJob.error_message ||
            `Failed to export dataset "${datasetName}"`,
          variant: "destructive",
        });

        // Mark the failed job as viewed so it won't show again after refresh
        markAsViewed({ jobId: job.id });
      }
    }
  }, [
    updatedJob,
    job.status,
    updateJob,
    datasetName,
    toast,
    markAsViewed,
    job.id,
  ]);

  // Show error toast for failed jobs that were loaded from API (after page refresh)
  // and haven't been viewed yet (viewed_at is null)
  useEffect(() => {
    if (
      job.status === DATASET_EXPORT_STATUS.FAILED &&
      !job.viewed_at &&
      !hasShownErrorToast.current
    ) {
      hasShownErrorToast.current = true;
      toast({
        title: "Export failed",
        description:
          job.error_message || `Failed to export dataset "${datasetName}"`,
        variant: "destructive",
      });

      // Mark as viewed so it won't show again
      markAsViewed({ jobId: job.id });
    }
  }, [
    job.status,
    job.viewed_at,
    job.error_message,
    job.id,
    datasetName,
    toast,
    markAsViewed,
  ]);

  const isLoading =
    job.status === DATASET_EXPORT_STATUS.PENDING ||
    job.status === DATASET_EXPORT_STATUS.PROCESSING;
  const isCompleted = job.status === DATASET_EXPORT_STATUS.COMPLETED;
  const isFailed = job.status === DATASET_EXPORT_STATUS.FAILED;

  const handleDownload = () => {
    if (job.download_url) {
      window.open(job.download_url, "_blank");
      removeJob(job.id);
    }
  };

  const handleDismiss = (e: React.MouseEvent) => {
    e.stopPropagation();
    removeJob(job.id);
  };

  // Render status indicator icon
  const renderStatusIndicator = () => {
    if (isLoading) {
      return (
        <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" />
      );
    }
    if (isCompleted) {
      return <CheckCircle2 className="size-4 shrink-0 text-green-600" />;
    }
    if (isFailed) {
      return <AlertCircle className="size-4 shrink-0 text-destructive" />;
    }
    return null;
  };

  // Render action button (shown on hover or for failed state)
  const renderActionButton = () => {
    if (isLoading && isHovered) {
      return (
        <TooltipWrapper content="Cancel">
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={handleDismiss}
            className="size-5"
          >
            <X className="size-4 text-muted-foreground" />
          </Button>
        </TooltipWrapper>
      );
    }

    if (isCompleted && isHovered) {
      return (
        <TooltipWrapper content="Download">
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={handleDownload}
            className="size-5"
          >
            <Download className="size-4 text-primary" />
          </Button>
        </TooltipWrapper>
      );
    }

    if (isFailed) {
      return (
        <TooltipWrapper content="Dismiss">
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={handleDismiss}
            className="size-5"
          >
            <X className="size-4 text-muted-foreground" />
          </Button>
        </TooltipWrapper>
      );
    }

    return null;
  };

  return (
    <div
      className={cn(
        "flex items-center gap-2 py-2 border-b last:border-b-0",
        isCompleted && "cursor-pointer hover:bg-muted/50",
      )}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      onClick={isCompleted ? handleDownload : undefined}
    >
      <div className="flex min-w-0 flex-1 items-center gap-2">
        {renderStatusIndicator()}
        <span className="truncate text-sm">{datasetName}</span>
        {isFailed && (
          <span className="shrink-0 text-xs text-destructive">Failed</span>
        )}
        {isCompleted && (
          <span className="shrink-0 text-xs text-green-600">Ready</span>
        )}
        {isLoading && (
          <span className="shrink-0 text-xs text-muted-foreground">
            Exporting...
          </span>
        )}
      </div>
      <div className="flex shrink-0 items-center">{renderActionButton()}</div>
    </div>
  );
};

const DatasetExportPanel: React.FC = () => {
  const activeJobs = useActiveExportJobs();
  const hasActiveJobs = useHasActiveExportJobs();
  const isPanelExpanded = useIsPanelExpanded();
  const togglePanelExpanded = useTogglePanelExpanded();
  const removeJob = useRemoveExportJob();
  const hydrateFromApi = useHydrateFromApi();
  const isHydrated = useIsHydrated();

  // Fetch all export jobs on mount to restore state after page refresh
  const { data: apiJobs } = useDatasetExportJobs({
    enabled: !isHydrated,
  });

  // Hydrate store from API data
  useEffect(() => {
    if (apiJobs && !isHydrated) {
      hydrateFromApi(apiJobs);
    }
  }, [apiJobs, isHydrated, hydrateFromApi]);

  if (!hasActiveJobs) {
    return null;
  }

  const pendingJobs = activeJobs.filter(
    (j) =>
      j.job.status === DATASET_EXPORT_STATUS.PENDING ||
      j.job.status === DATASET_EXPORT_STATUS.PROCESSING,
  );

  const completedJobs = activeJobs.filter(
    (j) => j.job.status === DATASET_EXPORT_STATUS.COMPLETED,
  );

  const pendingCount = pendingJobs.length;
  const completedCount = completedJobs.length;
  const hasOnlyCompleted = pendingCount === 0 && completedCount > 0;

  const getStatusText = () => {
    if (pendingCount > 0) {
      return "Preparing download";
    }
    if (completedCount > 0) {
      return "Download ready";
    }
    // Fallback for failed-only or mixed states
    return "Preparing download";
  };

  const handleClosePanel = (e: React.MouseEvent) => {
    e.stopPropagation();
    // Remove all jobs when closing panel
    activeJobs.forEach((jobInfo) => removeJob(jobInfo.job.id));
  };

  return (
    <Card
      className={cn(
        "fixed bottom-0 right-4 w-72 shadow-lg z-40",
        "transition-all duration-200 ease-in-out",
      )}
    >
      <CardHeader
        className="flex cursor-pointer flex-row items-center justify-between border-b px-3 py-2.5"
        onClick={togglePanelExpanded}
      >
        <div className="flex items-center gap-2">
          {hasOnlyCompleted ? (
            <CheckCircle2 className="size-4 text-green-600" />
          ) : null}
          <CardTitle className="text-sm font-medium">
            {getStatusText()}
          </CardTitle>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon-sm" className="size-6">
            {isPanelExpanded ? (
              <ChevronDown className="size-4" />
            ) : (
              <ChevronUp className="size-4" />
            )}
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            className="size-6"
            onClick={handleClosePanel}
          >
            <X className="size-4" />
          </Button>
        </div>
      </CardHeader>

      {isPanelExpanded && (
        <CardContent className="px-3 pb-2 pt-0">
          <div className="max-h-48 overflow-y-auto">
            {activeJobs.map((jobInfo) => (
              <ExportJobItem key={jobInfo.job.id} jobInfo={jobInfo} />
            ))}
          </div>
        </CardContent>
      )}
    </Card>
  );
};

export default DatasetExportPanel;
