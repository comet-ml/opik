import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";

/**
 * Resolves the chart date-range default for a project, overriding it for the seeded demo project.
 *
 * The demo project is compressed into the last ~10 hours so its trace/span ids clear the UUIDv7
 * ingestion window. Charts bucket by that id-embedded timestamp and the interval follows the
 * selected range (anything over 3 days buckets daily), so the workspace-wide 30-day default would
 * collapse the entire demo into a single bar. 24 hours buckets hourly and renders the curve.
 *
 * Spread the result into useMetricDateRangeWithQueryAndStorage. `initSyncReady` matters: the
 * project name arrives asynchronously, and without it the 30-day placeholder would be pinned into
 * the URL on mount and win over the demo default permanently.
 *
 * Every consumer sharing one date-range key must pass the same default — the Logs page has three
 * (useLogsType, TracesSpansTab, ThreadsTab) — otherwise whichever mounts first decides, and the
 * result depends on mount order.
 */
export const useDemoProjectDateRangeDefault = (projectId?: string) => {
  const { data: project, isPending } = useProjectById(
    { projectId: projectId! },
    { enabled: Boolean(projectId) },
  );

  return {
    defaultValue:
      project?.name === DEMO_PROJECT_NAME
        ? DATE_RANGE_PRESET_PAST_24_HOURS
        : DEFAULT_DATE_PRESET,
    // A disabled query stays pending forever, so treat "no project to look up" as settled rather
    // than blocking the URL sync indefinitely.
    initSyncReady: projectId ? !isPending : true,
  };
};
