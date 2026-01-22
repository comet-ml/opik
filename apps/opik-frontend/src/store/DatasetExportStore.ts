import { create } from "zustand";
import { DatasetExportJob, DATASET_EXPORT_STATUS } from "@/types/datasets";

/**
 * Constructs a display name for an export job, including version info if available.
 * @param datasetName - The base dataset name
 * @param versionId - The version ID (if present, version name will be included)
 * @param versionName - The version name to display
 * @returns Formatted display name like "Dataset Name" or "Dataset Name (v2)"
 */
export const buildExportDisplayName = (
  datasetName: string | undefined,
  versionId: string | undefined,
  versionName: string | undefined,
): string => {
  const baseName = datasetName || "Unknown Dataset";
  const versionSuffix = versionId && versionName ? ` (${versionName})` : "";
  return `${baseName}${versionSuffix}`;
};

/**
 * Parameters for the handleExportSuccess helper function.
 */
export interface HandleExportSuccessParams {
  job: DatasetExportJob;
  datasetName: string | undefined;
  versionId: string | undefined;
  versionName: string | undefined;
  addExportJob: (job: DatasetExportJob, displayName: string) => void;
}

/**
 * Shared helper for handling successful export job creation.
 * Builds the display name and adds the job to the store.
 * Note: Does NOT auto-expand the panel - the panel shows collapsed with job count.
 */
export const handleExportSuccess = ({
  job,
  datasetName,
  versionId,
  versionName,
  addExportJob,
}: HandleExportSuccessParams): void => {
  const exportName = buildExportDisplayName(
    datasetName,
    versionId,
    versionName,
  );
  addExportJob(job, exportName);
};

export interface ExportJobInfo {
  job: DatasetExportJob;
  datasetName: string;
  /** Whether the user has downloaded this export file */
  isDownloaded?: boolean;
}

interface DatasetExportState {
  // Active export jobs being tracked
  activeJobs: Map<string, ExportJobInfo>;
  // Whether the panel is expanded
  isPanelExpanded: boolean;
  // Whether the store has been hydrated from API
  isHydrated: boolean;

  // Actions
  addJob: (job: DatasetExportJob, datasetName: string) => void;
  updateJob: (job: DatasetExportJob) => void;
  removeJob: (jobId: string) => void;
  markJobAsDownloaded: (jobId: string) => void;
  togglePanelExpanded: () => void;
  setPanelExpanded: (expanded: boolean) => void;
  hydrateFromApi: (jobs: DatasetExportJob[]) => void;
}

const useDatasetExportStore = create<DatasetExportState>((set) => ({
  activeJobs: new Map(),
  isPanelExpanded: false,
  isHydrated: false,

  addJob: (job, datasetName) =>
    set((state) => {
      // Create new Map with the new job first (most recent at top)
      const newJobs = new Map<string, ExportJobInfo>();
      newJobs.set(job.id, { job, datasetName, isDownloaded: false });
      // Then add existing jobs
      for (const [id, jobInfo] of state.activeJobs) {
        if (id !== job.id) {
          newJobs.set(id, jobInfo);
        }
      }
      // Don't auto-expand panel when adding new jobs - let user control it
      return { activeJobs: newJobs };
    }),

  updateJob: (job) =>
    set((state) => {
      const existing = state.activeJobs.get(job.id);
      if (!existing) return state;

      const newJobs = new Map(state.activeJobs);
      newJobs.set(job.id, { ...existing, job });
      return { activeJobs: newJobs };
    }),

  removeJob: (jobId) =>
    set((state) => {
      const newJobs = new Map(state.activeJobs);
      newJobs.delete(jobId);
      return { activeJobs: newJobs };
    }),

  markJobAsDownloaded: (jobId) =>
    set((state) => {
      const existing = state.activeJobs.get(jobId);
      if (!existing) return state;

      const newJobs = new Map(state.activeJobs);
      newJobs.set(jobId, { ...existing, isDownloaded: true });
      return { activeJobs: newJobs };
    }),

  togglePanelExpanded: () =>
    set((state) => ({ isPanelExpanded: !state.isPanelExpanded })),

  setPanelExpanded: (expanded) => set({ isPanelExpanded: expanded }),

  hydrateFromApi: (jobs) =>
    set((state) => {
      // Only hydrate once and don't overwrite existing jobs
      if (state.isHydrated) return state;

      // Create a new Map preserving the order from the API (most recent first)
      // First add all jobs from API in order, then merge any existing jobs
      const newJobs = new Map<string, ExportJobInfo>();

      // Add API jobs first (they come ordered from backend)
      for (const job of jobs) {
        newJobs.set(job.id, {
          job,
          datasetName: buildExportDisplayName(
            job.dataset_name,
            job.dataset_version_id,
            job.version_name,
          ),
        });
      }

      // Merge any existing jobs that aren't in the API response
      // (these would be jobs added during the current session before hydration)
      for (const [id, jobInfo] of state.activeJobs) {
        if (!newJobs.has(id)) {
          newJobs.set(id, jobInfo);
        }
      }

      return {
        activeJobs: newJobs,
        isHydrated: true,
        // Don't auto-expand panel on hydration - let user control it
      };
    }),
}));

// Selector hooks
export const useActiveExportJobs = () =>
  useDatasetExportStore((state) => Array.from(state.activeJobs.values()));

export const useHasActiveExportJobs = () =>
  useDatasetExportStore((state) => state.activeJobs.size > 0);

export const useIsPanelExpanded = () =>
  useDatasetExportStore((state) => state.isPanelExpanded);

export const useAddExportJob = () =>
  useDatasetExportStore((state) => state.addJob);

export const useUpdateExportJob = () =>
  useDatasetExportStore((state) => state.updateJob);

export const useRemoveExportJob = () =>
  useDatasetExportStore((state) => state.removeJob);

export const useMarkJobAsDownloaded = () =>
  useDatasetExportStore((state) => state.markJobAsDownloaded);

export const useTogglePanelExpanded = () =>
  useDatasetExportStore((state) => state.togglePanelExpanded);

export const useSetPanelExpanded = () =>
  useDatasetExportStore((state) => state.setPanelExpanded);

export const useHydrateFromApi = () =>
  useDatasetExportStore((state) => state.hydrateFromApi);

export const useIsHydrated = () =>
  useDatasetExportStore((state) => state.isHydrated);

/**
 * Checks if there's already an in-progress export job for the given dataset and version.
 * @param datasetId - The dataset ID to check
 * @param datasetVersionId - Optional version ID to check
 * @returns true if there's an in-progress job, false otherwise
 */
export const useHasInProgressJob = (
  datasetId: string,
  datasetVersionId?: string,
) => {
  return useDatasetExportStore((state) => {
    for (const jobInfo of state.activeJobs.values()) {
      const job = jobInfo.job;
      // Check if it's the same dataset
      if (job.dataset_id !== datasetId) continue;

      // Check version match (normalize null and undefined to undefined for comparison)
      const jobVersionId = job.dataset_version_id ?? undefined;
      const normalizedVersionId = datasetVersionId ?? undefined;
      if (jobVersionId !== normalizedVersionId) continue;

      // Check if job is in progress (PENDING or PROCESSING)
      if (
        job.status === DATASET_EXPORT_STATUS.PENDING ||
        job.status === DATASET_EXPORT_STATUS.PROCESSING
      ) {
        return true;
      }
    }
    return false;
  });
};

export default useDatasetExportStore;
