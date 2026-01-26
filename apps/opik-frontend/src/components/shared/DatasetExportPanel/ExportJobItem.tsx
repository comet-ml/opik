import React, { useEffect, useState } from "react";
import {
  Download,
  Loader2,
  X,
  CheckCircle2,
  AlertCircle,
  ExternalLink,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import {
  useRemoveExportJob,
  useUpdateExportJob,
  useMarkJobAsDownloaded,
  ExportJobInfo,
} from "@/store/DatasetExportStore";
import useDatasetExportJob from "@/api/datasets/useDatasetExportJob";
import useMarkExportJobViewedMutation from "@/api/datasets/useMarkExportJobViewedMutation";
import useDeleteDatasetExportJobMutation from "@/api/datasets/useDeleteDatasetExportJobMutation";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useToast } from "@/components/ui/use-toast";
import {
  isJobLoading,
  isJobCompleted,
  isJobFailed,
  isTerminalStatus,
} from "./utils";
import { getExportJobDownloadUrl } from "@/api/datasets/exportJobHelpers";

// Polling interval for checking job status (5 seconds)
const POLLING_INTERVAL_MS = 5000;

interface ExportJobItemProps {
  jobInfo: ExportJobInfo;
}

const ExportJobItem: React.FC<ExportJobItemProps> = ({ jobInfo }) => {
  const { job, datasetName, isDownloaded } = jobInfo;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const removeJob = useRemoveExportJob();
  const updateJob = useUpdateExportJob();
  const markJobAsDownloaded = useMarkJobAsDownloaded();
  const [isHovered, setIsHovered] = useState(false);
  const { toast } = useToast();

  // Poll for job status updates (every 5 seconds for PENDING/PROCESSING jobs)
  // Also enable for completed jobs to ensure we capture the status change
  // And for failed jobs that haven't been viewed so we can refetch after marking as viewed
  const { data: updatedJob } = useDatasetExportJob(
    {
      jobId: job.id,
    },
    {
      enabled:
        isJobLoading(job.status) ||
        isJobCompleted(job.status) ||
        (isJobFailed(job.status) && !job.viewed_at),
      // Poll every 5 seconds until terminal status
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return isTerminalStatus(status) ? false : POLLING_INTERVAL_MS;
      },
    },
  );

  // Mark as viewed mutation - query invalidation is handled in the hook
  const { mutate: markAsViewed } = useMarkExportJobViewedMutation();

  // Delete mutation
  const { mutate: deleteExportJob, isPending: isDeleting } =
    useDeleteDatasetExportJobMutation();

  // Combined effect: Update store when job status changes, and show toast for failed jobs
  // This handles both jobs that fail during polling and jobs loaded from API
  useEffect(() => {
    const currentJob = updatedJob || job;

    // Update store when job status changes
    if (updatedJob && updatedJob.status !== job.status) {
      updateJob(updatedJob);
    }

    // Show error toast for failed jobs that haven't been viewed yet (viewed_at is null)
    const shouldShowToast =
      isJobFailed(currentJob.status) && !currentJob.viewed_at;

    if (shouldShowToast) {
      toast({
        title: "Export failed",
        description:
          currentJob.error_message ||
          `Failed to export dataset "${datasetName}"`,
        variant: "destructive",
      });

      // Mark as viewed so it won't show again
      // Query invalidation in the mutation hook will automatically refetch the job
      markAsViewed({ jobId: currentJob.id });
    }
  }, [
    updatedJob,
    job,
    job.status,
    job.viewed_at,
    updateJob,
    datasetName,
    toast,
    markAsViewed,
  ]);

  const isLoading = isJobLoading(job.status);
  const isCompleted = isJobCompleted(job.status);
  const isFailed = isJobFailed(job.status);

  const handleDownload = () => {
    // Use the proxy download endpoint instead of direct MinIO URL
    const downloadUrl = getExportJobDownloadUrl(job.id);
    window.open(downloadUrl, "_blank");
    // Mark as downloaded in the store
    markJobAsDownloaded(job.id);
  };

  const handleDismiss = (e: React.MouseEvent) => {
    e.stopPropagation();
    removeJob(job.id);
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    deleteExportJob(
      { jobId: job.id },
      {
        onSuccess: () => {
          removeJob(job.id);
          // Don't show success notification for delete
        },
        onError: () => {
          toast({
            title: "Delete failed",
            description: "Failed to delete the export file. Please try again.",
            variant: "destructive",
          });
        },
      },
    );
  };

  // Render status indicator icon
  const renderStatusIndicator = () => {
    if (isLoading) {
      return (
        <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" />
      );
    }
    if (isCompleted) {
      // Grey checkmark if downloaded, green if ready but not downloaded
      return (
        <CheckCircle2
          className={cn(
            "size-4 shrink-0",
            isDownloaded ? "text-muted-foreground" : "text-green-600",
          )}
        />
      );
    }
    if (isFailed) {
      return <AlertCircle className="size-4 shrink-0 text-destructive" />;
    }
    return null;
  };

  const handleGoToDataset = (e: React.MouseEvent) => {
    e.stopPropagation();
    window.location.href = `/${workspaceName}/datasets/${job.dataset_id}/items`;
  };

  // Render action button (shown on hover for completed, always for failed)
  const renderActionButton = () => {
    if (isCompleted && isHovered) {
      return (
        <div className="flex items-center gap-0.5">
          <TooltipWrapper content="Go to dataset">
            <Button
              variant="ghost"
              size="icon-sm"
              className="size-5"
              onClick={handleGoToDataset}
            >
              <ExternalLink className="size-4 text-foreground-secondary" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Download">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={(e) => {
                e.stopPropagation();
                handleDownload();
              }}
              className="size-5"
            >
              <Download className="size-4 text-foreground-secondary" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Delete">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleDelete}
              disabled={isDeleting}
              className="size-5"
            >
              <X className="size-4 text-muted-foreground" />
            </Button>
          </TooltipWrapper>
        </div>
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
        "flex w-full items-center gap-2 border-b px-3 py-2 last:border-b-0 transition-colors",
        isCompleted && "cursor-pointer hover:bg-muted/50",
      )}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      onClick={isCompleted ? handleDownload : undefined}
    >
      <div className="flex min-w-0 flex-1 items-center gap-2">
        {renderStatusIndicator()}
        <span className="min-w-0 flex-1 truncate text-sm">{datasetName}</span>
        <div className="shrink-0">
          {isFailed && <span className="text-xs text-destructive">Failed</span>}
          {isCompleted && isDownloaded && (
            <span className="text-xs text-muted-foreground">Downloaded</span>
          )}
          {isCompleted && !isDownloaded && (
            <span className="text-xs text-green-600">Ready</span>
          )}
          {isLoading && (
            <span className="text-xs text-muted-foreground">Exporting...</span>
          )}
        </div>
      </div>
      <div className="flex shrink-0 items-center">{renderActionButton()}</div>
    </div>
  );
};

export default ExportJobItem;
