import { DEMO_PROJECT_NAMES } from "@/constants/shared";
import { DateRangePreset } from "@/shared/DateRangeSelect";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./MetricDateRangeSelect/constants";

/**
 * Spread straight into useMetricDateRangeWithQueryAndStorage.
 *
 * More than a default: it also carries which storage slot to persist into (`storageKeySuffix`), so
 * callers should pass the whole object rather than pick the default out of it.
 *
 * Returned for every project, not just the demo one — an ordinary project gets the workspace
 * default and an empty suffix.
 */
export type ProjectDateRangeConfig = {
  defaultValue: DateRangePreset;
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
 * Both fields matter:
 *
 * - `defaultValue` is the 24h override itself.
 * - `storageKeySuffix` gives the demo project its own persistence slot. The stored range is sticky
 *   across projects and outranks any default, so without this the demo would inherit whatever range
 *   the user last picked on a real project and never apply 24h at all. It is scoped to the matched
 *   project name, so each name in DEMO_PROJECT_NAMES keeps its own range.
 *
 * Pure on purpose: a page whose consumers share one date-range key must feed them all the same
 * values, or whichever mounts first decides and the result turns on mount order. Resolving once in
 * the parent and passing the result down makes that structurally impossible — see LogsPage, which
 * has three consumers (useLogsType, TracesSpansTab, ThreadsTab).
 *
 * Callers must resolve the project before calling: both pages gate their content mount on the
 * project query, which is what makes the captured default correct (use-local-storage-state captures
 * `defaultValue` once). A name of undefined therefore means "not the demo project" — a failed lookup
 * simply leaves the workspace default in place.
 */
export const resolveProjectDateRangeConfig = (
  projectName: string | undefined,
): ProjectDateRangeConfig => {
  const isDemoProject =
    projectName !== undefined && DEMO_PROJECT_NAMES.includes(projectName);

  return {
    defaultValue: isDemoProject
      ? DATE_RANGE_PRESET_PAST_24_HOURS
      : DEFAULT_DATE_PRESET,
    // Scoped to the matched name so each demo project keeps its own range, and none of them share
    // the slot real projects persist into.
    storageKeySuffix: isDemoProject ? `-${projectName}` : "",
  };
};
