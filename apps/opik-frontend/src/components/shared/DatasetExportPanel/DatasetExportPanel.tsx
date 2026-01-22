import React, { useEffect, useState } from "react";
import { ChevronDown, ChevronUp, X, CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  useActiveExportJobs,
  useHasActiveExportJobs,
  useIsPanelExpanded,
  useTogglePanelExpanded,
  useRemoveExportJob,
  useHydrateFromApi,
  useIsHydrated,
} from "@/store/DatasetExportStore";
import useDatasetExportJobs from "@/api/datasets/useDatasetExportJobs";
import useDeleteDatasetExportJobMutation from "@/api/datasets/useDeleteDatasetExportJobMutation";
import { DATASET_EXPORT_STATUS } from "@/types/datasets";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExportJobItem from "./ExportJobItem";
import { isJobLoading, isJobCompleted, isJobFailed } from "./utils";

const DatasetExportPanel: React.FC = () => {
  const activeJobs = useActiveExportJobs();
  const hasActiveJobs = useHasActiveExportJobs();
  const isPanelExpanded = useIsPanelExpanded();
  const togglePanelExpanded = useTogglePanelExpanded();
  const removeJob = useRemoveExportJob();
  const hydrateFromApi = useHydrateFromApi();
  const isHydrated = useIsHydrated();
  const [showCloseConfirm, setShowCloseConfirm] = useState(false);
  const { mutate: deleteExportJob } = useDeleteDatasetExportJobMutation();

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

  const pendingJobs = activeJobs.filter((j) => isJobLoading(j.job.status));

  const completedJobs = activeJobs.filter(
    (j) => j.job.status === DATASET_EXPORT_STATUS.COMPLETED,
  );

  const pendingCount = pendingJobs.length;
  const completedCount = completedJobs.length;
  const hasPendingJobs = pendingCount > 0;

  // Count how many completed jobs are downloaded vs ready
  const downloadedCount = completedJobs.filter((j) => j.isDownloaded).length;
  const readyCount = completedCount - downloadedCount;
  const allDownloaded =
    completedCount > 0 &&
    downloadedCount === completedCount &&
    pendingCount === 0;

  const getStatusText = () => {
    // If at least one is being prepared: "Preparing download"
    if (hasPendingJobs) {
      return "Preparing download";
    }
    // If all in downloaded state: "Downloaded"
    if (allDownloaded) {
      return "Downloaded";
    }
    // If none being prepared and at least one Ready: "Download ready"
    if (readyCount > 0) {
      return "Download ready";
    }
    // Fallback (shouldn't happen, but handle edge cases)
    return "Preparing download";
  };

  const removeAllJobs = () => {
    // Remove all jobs when closing panel
    // Use slice() to create a defensive copy to avoid skipping items during iteration
    activeJobs.slice().forEach((jobInfo) => {
      const { job } = jobInfo;

      // For completed/failed jobs, call delete API to permanently dismiss them
      // This ensures they won't reappear after page refresh
      if (isJobCompleted(job.status) || isJobFailed(job.status)) {
        deleteExportJob({ jobId: job.id });
      }

      // Always remove from local store
      removeJob(job.id);
    });
  };

  const handleClosePanel = (e: React.MouseEvent) => {
    e.stopPropagation();
    // Always show confirmation dialog when closing the panel to clear all jobs
    setShowCloseConfirm(true);
  };

  const handleConfirmClose = () => {
    setShowCloseConfirm(false);
    removeAllJobs();
  };

  return (
    <Card className="fixed bottom-0 right-4 z-[110] w-72 shadow-lg transition-all duration-200 ease-in-out">
      <CardHeader
        className="flex cursor-pointer flex-row items-center justify-between border-b px-3 py-2.5"
        onClick={togglePanelExpanded}
      >
        <div className="flex items-center gap-2">
          {readyCount > 0 && !hasPendingJobs && (
            <CheckCircle2 className="size-4 text-green-600" />
          )}
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

      {/* Always render ExportJobItem components for polling, but only show when expanded */}
      <div className={isPanelExpanded ? "" : "hidden"}>
        <CardContent className="p-0">
          <div
            className={
              activeJobs.length > 6
                ? "max-h-64 overflow-y-auto"
                : "overflow-visible"
            }
          >
            {activeJobs.map((jobInfo) => (
              <ExportJobItem key={jobInfo.job.id} jobInfo={jobInfo} />
            ))}
          </div>
        </CardContent>
      </div>

      <ConfirmDialog
        open={showCloseConfirm}
        setOpen={setShowCloseConfirm}
        onConfirm={handleConfirmClose}
        title="Delete export files?"
        description="This will permanently remove all generated export files. You will need to generate new files for download..."
        confirmText="Delete"
        confirmButtonVariant="destructive"
        cancelText="Cancel"
      />
    </Card>
  );
};

export default DatasetExportPanel;
