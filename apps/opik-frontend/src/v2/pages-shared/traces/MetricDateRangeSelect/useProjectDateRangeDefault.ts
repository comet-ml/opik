import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { DateRangePreset } from "@/shared/DateRangeSelect";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";

/**
 * Keeps the demo project's picked range out of the range shared by real projects.
 *
 * Built from the full demo project name rather than a short literal like `-demo`, so it cannot
 * collide with a customer's own project that happens to be named "demo", and so it follows any
 * rename of DEMO_PROJECT_NAME on its own.
 */
const DEMO_STORAGE_KEY_SUFFIX = `-${DEMO_PROJECT_NAME}`;

/**
 * Spread straight into useMetricDateRangeWithQueryAndStorage.
 *
 * Returned for every project, not just the demo one — an ordinary project gets the workspace
 * default and an empty suffix.
 */
export type ProjectDateRangeDefault = {
  defaultValue: DateRangePreset;
  initSyncReady: boolean;
  storageKeySuffix: string;
};

/**
 * The chart date-range default for a project, overridden for the seeded demo project.
 *
 * The demo project is compressed into the last ~10 hours so its trace/span ids clear the UUIDv7
 * ingestion window. Charts bucket by that id-embedded timestamp and the interval follows the
 * selected range (anything over 3 days buckets daily), so the workspace-wide 30-day default would
 * collapse the entire demo into a single bar. 24 hours buckets hourly and renders the curve.
 *
 * All three fields matter:
 *
 * - `defaultValue` is the 24h override itself.
 * - `initSyncReady` holds the URL sync until the project name is known. Without it the 30-day
 *   placeholder gets pinned into the URL first and wins permanently.
 * - `storageKeySuffix` gives the demo project its own persistence slot. The stored range is sticky
 *   across projects and outranks any default, so without this the demo would inherit whatever range
 *   the user last picked on a real project and never apply 24h at all.
 *
 * Pure on purpose: a page whose consumers share one date-range key must feed them all the same
 * values, or whichever mounts first decides and the result turns on mount order. Resolving once in
 * the parent and passing the result down makes that structurally impossible — see LogsPage, which
 * has three consumers (useLogsType, TracesSpansTab, ThreadsTab).
 *
 * @param projectName the project's name, or undefined while it is not known
 * @param isSettled whether the name is as resolved as it is going to get. A failed lookup counts as
 *   settled: the override simply does not apply and the workspace default stands, which is better
 *   than never syncing the URL at all.
 */
export const resolveProjectDateRangeDefault = (
  projectName: string | undefined,
  isSettled: boolean,
): ProjectDateRangeDefault => {
  const isDemoProject = projectName === DEMO_PROJECT_NAME;

  return {
    defaultValue: isDemoProject
      ? DATE_RANGE_PRESET_PAST_24_HOURS
      : DEFAULT_DATE_PRESET,
    initSyncReady: isSettled,
    storageKeySuffix: isDemoProject ? DEMO_STORAGE_KEY_SUFFIX : "",
  };
};

/**
 * resolveProjectDateRangeDefault for a caller that holds only a project id.
 *
 *
 * Prefer the resolver directly when the project is already in scope — a parent that owns the query
 * should pass its result down rather than have children re-observe it (performance.md, "Don't
 * refetch what the parent already has").
 */
export const useProjectDateRangeDefault = (projectId?: string) => {
  const { data: project, isPending } = useProjectById(
    { projectId: projectId! },
    { enabled: Boolean(projectId), refetchOnMount: false },
  );

  // Settled means "we know as much as we ever will". Nothing to look up counts, and so does a
  // failed lookup — react-query reports a cached error as not-pending with no data, and falling
  // back to the workspace default beats hanging the URL sync forever.
  return resolveProjectDateRangeDefault(
    project?.name,
    projectId ? !isPending : true,
  );
};
