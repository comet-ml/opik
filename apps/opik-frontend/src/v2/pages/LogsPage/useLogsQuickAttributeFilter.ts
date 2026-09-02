import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, NumberParam, useQueryParam } from "use-query-params";
import { Filter } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import {
  QuickAttributeFilterApi,
  QuickAttributeFilterFactory,
} from "@/shared/filter-chips/QuickAttributeFilterContext";
import {
  QuickFilterLocalChips,
  useQuickAttributeFilterActions,
} from "@/shared/filter-chips/hooks/useQuickAttributeFilterActions";
import { queryBuilderFilterEventProps } from "@/shared/filter-chips/hooks/useFilterChipsAnalytics";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  SPAN_DEFAULT_PINNED_CHIPS,
  TRACE_DEFAULT_PINNED_CHIPS,
} from "@/v2/pages-shared/traces/traceChipDefinitions";

interface LogsView {
  logsType: LOGS_TYPE;
  type: TRACE_DATA_TYPE;
  urlKey: string;
  tableId: string;
  defaultPinned: string[];
}

export const TRACES_VIEW: LogsView = {
  logsType: LOGS_TYPE.traces,
  type: TRACE_DATA_TYPE.traces,
  urlKey: `${TRACE_DATA_TYPE.traces}_filters`,
  tableId: "logs.traces",
  defaultPinned: TRACE_DEFAULT_PINNED_CHIPS,
};

export const SPANS_VIEW: LogsView = {
  logsType: LOGS_TYPE.spans,
  type: TRACE_DATA_TYPE.spans,
  urlKey: `${TRACE_DATA_TYPE.spans}_filters`,
  tableId: "logs.spans",
  defaultPinned: SPAN_DEFAULT_PINNED_CHIPS,
};

/**
 * Writes a filter row into a Logs view that is not mounted. That view's chip
 * state is reached through the same URL parameter and local-storage key that
 * `useFilterChips` takes over once it mounts. The row is appended as-is, never
 * re-derived from chip definitions, so filters this hook knows nothing about
 * survive untouched.
 *
 * The chip events are emitted here too. `useFilterChips` normally owns them,
 * but it is not mounted for the destination, so without this the funnels would
 * count a quick filter on the Traces or Spans tab and miss the same click made
 * from Threads or from a selected span.
 */
const useHandoffWriter = (view: LogsView) => {
  const [, setRawFilters] = useQueryParam<Filter[] | undefined>(
    view.urlKey,
    JsonParam,
    { updateType: "replaceIn" },
  );
  const [, setPinnedIds] = useLocalStorageState<string[]>(
    `chips:pinnedConfig:${view.tableId}`,
    { defaultValue: view.defaultPinned },
  );

  return useCallback(
    (chipId: string, row: Filter) => {
      setRawFilters((prevRaw) => {
        // `JsonParam` hands back whatever the query string held, so a
        // hand-edited URL can carry holes. `sanitizeFilters` drops them on
        // read; drop them here too rather than dereference one.
        const existing = Array.isArray(prevRaw) ? prevRaw.filter(Boolean) : [];
        const duplicate = existing.some(
          (r) =>
            r.field === row.field &&
            (r.key ?? "") === (row.key ?? "") &&
            r.operator === row.operator &&
            String(r.value) === String(row.value),
        );
        if (duplicate) return prevRaw;

        const next = [...existing, row];
        // Report what the destination will actually apply. `sanitizeFilters`
        // drops rows the chip cannot hold, and the mounted path reports the
        // sanitized rows only — so filter the same way rather than counting a
        // stale row the destination is about to discard.
        trackEvent(OpikEvent.FILTER_APPLIED, {
          ...queryBuilderFilterEventProps(
            chipId,
            next.filter((r) => r.field === row.field && isFilterValid(r)),
          ),
          table_id: view.tableId,
        });
        return next;
      });
      // Unconditional, to match `useFilterChips.pinChip`, which reports every
      // call even when the chip is pinned already.
      trackEvent(OpikEvent.FILTER_PINNED, {
        filter_name: chipId,
        table_id: view.tableId,
      });
      setPinnedIds((prev = view.defaultPinned) =>
        prev.includes(chipId) ? prev : [...prev, chipId],
      );
    },
    [view, setRawFilters, setPinnedIds],
  );
};

/**
 * The quick-filter behaviour for one destination view. The filter applies to
 * the mounted chips when that view is already on screen, and is written into
 * the view plus followed by the table when it is not.
 *
 * `local` belongs to the view on screen, so the caller passes it only to the
 * matching view. That is what makes a write into the wrong table impossible to
 * express, rather than only unreachable while `handoff` is set.
 */
const useViewQuickAttributeFilter = (
  view: LogsView,
  isOnScreen: boolean,
  onLogsTypeChange: (logsType: LOGS_TYPE) => void,
  setPage: (page: number) => void,
  local: QuickFilterLocalChips | undefined,
): QuickAttributeFilterApi => {
  const write = useHandoffWriter(view);

  const handoff = useCallback(
    (chipId: string, row: Filter) => {
      write(chipId, row);
      // The destination shows a fresh result set, so the page the user was on
      // would land them past the end of it.
      setPage(1);
      onLogsTypeChange(view.logsType);
    },
    [view, write, setPage, onLogsTypeChange],
  );

  return useQuickAttributeFilterActions({
    type: view.type,
    tableId: view.tableId,
    local: isOnScreen ? local : undefined,
    handoff: isOnScreen ? undefined : handoff,
  });
};

interface UseLogsQuickAttributeFilterArgs {
  logsType: LOGS_TYPE;
  onLogsTypeChange: (logsType: LOGS_TYPE) => void;
  // The mounted view's own chips. Omitted by Threads, whose table has no
  // metadata / input / output fields and so can never hold these filters.
  local?: QuickFilterLocalChips;
}

/**
 * The quick-filter behaviour for every Logs view, in one place.
 *
 * The attributes in the details panel belong to one entity, and that entity's
 * view owns the filter: if it is already on screen the filter applies in place,
 * otherwise it is written there and the table follows. The panel resolves the
 * entity, so this returns one api per entity rather than picking here.
 */
const useLogsQuickAttributeFilter = ({
  logsType,
  onLogsTypeChange,
  local,
}: UseLogsQuickAttributeFilterArgs): QuickAttributeFilterFactory => {
  const [, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const tracesApi = useViewQuickAttributeFilter(
    TRACES_VIEW,
    logsType === TRACES_VIEW.logsType,
    onLogsTypeChange,
    setPage,
    local,
  );
  const spansApi = useViewQuickAttributeFilter(
    SPANS_VIEW,
    logsType === SPANS_VIEW.logsType,
    onLogsTypeChange,
    setPage,
    local,
  );

  return useCallback(
    (entity) => (entity === "span" ? spansApi : tracesApi),
    [spansApi, tracesApi],
  );
};

export default useLogsQuickAttributeFilter;
