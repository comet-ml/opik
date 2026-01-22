import { create } from "zustand";
import { DatasetExportJob } from "@/types/datasets";

export interface ExportJobInfo {
  job: DatasetExportJob;
  datasetName: string;
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
      newJobs.set(job.id, { job, datasetName });
      // Then add existing jobs
      for (const [id, jobInfo] of state.activeJobs) {
        if (id !== job.id) {
          newJobs.set(id, jobInfo);
        }
      }
      return { activeJobs: newJobs, isPanelExpanded: true };
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
          datasetName: job.dataset_name || "Unknown Dataset",
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
        // Expand panel if there are jobs to show
        isPanelExpanded: newJobs.size > 0 ? true : state.isPanelExpanded,
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

export const useTogglePanelExpanded = () =>
  useDatasetExportStore((state) => state.togglePanelExpanded);

export const useSetPanelExpanded = () =>
  useDatasetExportStore((state) => state.setPanelExpanded);

export const useHydrateFromApi = () =>
  useDatasetExportStore((state) => state.hydrateFromApi);

export const useIsHydrated = () =>
  useDatasetExportStore((state) => state.isHydrated);

export default useDatasetExportStore;
