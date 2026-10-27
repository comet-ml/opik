import { useMemo } from "react";
import useDashboardWidgetQuery from "@/api/dashboards/useDashboardWidgetQuery";
import { comparisonWindow, QueryError } from "./helpers";
import useRefetchOnceSaved from "./useRefetchOnceSaved";

const MINUTE = 60_000;

type SavedQuery = Omit<
  Parameters<typeof useDashboardWidgetQuery>[0],
  "intervalStart" | "intervalEnd"
>;

type UseKpiComparisonParams = {
  savedQuery: SavedQuery;
  enabled: boolean;
  retryNotFound: boolean;
  intervalStart?: string;
  intervalEnd?: string;
  // The value's query: an open-ended range runs to when it was fetched, and no change shows while it refetches.
  value: { dataUpdatedAt: number; isFetching: boolean };
};

// A KPI's change: the widget's saved query run again on the window of the same length just before the dashboard's range.
export default function useKpiComparison({
  savedQuery,
  enabled,
  retryNotFound,
  intervalStart,
  intervalEnd,
  value,
}: UseKpiComparisonParams) {
  // An open-ended range ends when the value was fetched, to the minute, so refetches within one share the comparison.
  const fetchedMinute = Math.floor(value.dataUpdatedAt / MINUTE) * MINUTE;
  const window = useMemo(
    () =>
      enabled && intervalStart && (intervalEnd || fetchedMinute)
        ? comparisonWindow(intervalStart, intervalEnd, fetchedMinute)
        : null,
    [enabled, intervalStart, intervalEnd, fetchedMinute],
  );

  // A past window's rows don't change, so the comparison never refetches on its own: a new window brings a new run.
  const { data, error, isFetching, refetch } = useDashboardWidgetQuery(
    {
      ...savedQuery,
      intervalStart: window?.intervalStart,
      intervalEnd: window?.intervalEnd,
    },
    {
      enabled: Boolean(window),
      retryNotFound,
      staleTime: Infinity,
      refetchOnWindowFocus: false,
    },
  );
  useRefetchOnceSaved(
    retryNotFound,
    window ? (error as QueryError | null) : null,
    refetch,
  );

  return {
    // A failed or settling comparison shows none, rather than the change on rows from another moment.
    previousRows:
      window && !error && !isFetching && !value.isFetching
        ? data ?? null
        : null,
    comparedWith: window ? `previous ${window.label}` : undefined,
    // A closed range compares with the same window after a refresh, so it runs again; an open one moves with the value.
    refetch: () => {
      if (window && intervalEnd) refetch();
    },
  };
}
