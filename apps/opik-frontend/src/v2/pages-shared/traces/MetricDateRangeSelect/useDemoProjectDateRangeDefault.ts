import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
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
 * Resolves the chart date-range default for a project, overriding it for the seeded demo project.
 *
 * The demo project is compressed into the last ~10 hours so its trace/span ids clear the UUIDv7
 * ingestion window. Charts bucket by that id-embedded timestamp and the interval follows the
 * selected range (anything over 3 days buckets daily), so the workspace-wide 30-day default would
 * collapse the entire demo into a single bar. 24 hours buckets hourly and renders the curve.
 *
 * Spread the whole result into useMetricDateRangeWithQueryAndStorage — all three fields matter:
 *
 * - `defaultValue` is the 24h override itself.
 * - `initSyncReady` holds the URL sync until the project name lands. Without it the 30-day
 *   placeholder gets pinned into the URL first and wins permanently.
 * - `storageKeySuffix` gives the demo project its own persistence slot. The stored range is sticky
 *   across projects and outranks any default, so without this the demo would inherit whatever range
 *   the user last picked on a real project and never apply 24h at all.
 *
 * Every consumer sharing one date-range key must pass the same values — the Logs page has three
 * (useLogsType, TracesSpansTab, ThreadsTab) — otherwise whichever mounts first decides, and the
 * result depends on mount order.
 */
export const useDemoProjectDateRangeDefault = (projectId?: string) => {
  const { data: project, isPending } = useProjectById(
    { projectId: projectId! },
    { enabled: Boolean(projectId) },
  );
  const isDemoProject = project?.name === DEMO_PROJECT_NAME;

  return {
    defaultValue: isDemoProject
      ? DATE_RANGE_PRESET_PAST_24_HOURS
      : DEFAULT_DATE_PRESET,
    // A disabled query stays pending forever, so treat "no project to look up" as settled rather
    // than blocking the URL sync indefinitely.
    initSyncReady: projectId ? !isPending : true,
    storageKeySuffix: isDemoProject ? DEMO_STORAGE_KEY_SUFFIX : "",
  };
};
