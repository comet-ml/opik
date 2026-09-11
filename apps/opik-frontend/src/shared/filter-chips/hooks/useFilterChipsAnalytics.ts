import { useCallback, useEffect, useMemo, useRef } from "react";
import uniq from "lodash/uniq";
import { OpikEvent, OpikEventName, trackEvent } from "@/lib/analytics/tracking";
import { Filter } from "@/types/filters";
import {
  ChipDefinition,
  ChipValue,
  ChipValueMap,
  NumericChipValue,
  QueryBuilderChipValue,
  SingleSelectChipValue,
  TimeChipValue,
} from "@/shared/filter-chips/types";

export type FilterRemovedSource = "chip_x" | "clear_all" | "dialog" | "unpin";

// Shared with the quick-filter handoff, which applies a row into a view whose
// chip definitions it never builds. Every target it can reach is a
// query-builder chip, and this payload needs only the chip id and the rows.
export const queryBuilderFilterEventProps = (
  chipId: string,
  rows: Filter[],
): Record<string, unknown> => ({
  filter_name: chipId,
  operators: uniq(rows.map((r) => r.operator).filter(Boolean)),
  values: rows.map((r) => r.value),
});

const getFilterEventProps = (
  def: ChipDefinition,
  value: ChipValue,
): Record<string, unknown> => {
  const base = { filter_name: def.id };
  switch (def.kind) {
    case "boolean":
      return { ...base, operator: def.onOperator };
    case "single-select":
      return {
        ...base,
        operator: "=",
        value: (value as SingleSelectChipValue).value,
      };
    case "numeric": {
      const v = value as NumericChipValue;
      if (v.mode === "between")
        return { ...base, operator: "between", value: `${v.min}-${v.max}` };
      if (v.mode === "exactly")
        return { ...base, operator: "=", value: v.exact };
      if (v.mode === "atLeast")
        return { ...base, operator: ">=", value: v.min };
      return { ...base, operator: "<=", value: v.max };
    }
    case "time": {
      const v = value as TimeChipValue;
      if (v.mode === "exactly")
        return { ...base, operator: "exactly", value: v.at };
      if (v.mode === "before")
        return { ...base, operator: "before", value: v.before };
      if (v.mode === "after")
        return { ...base, operator: "after", value: v.after };
      return { ...base, operator: "between", value: `${v.start}/${v.end}` };
    }
    case "query-builder":
      return queryBuilderFilterEventProps(
        def.id,
        (value as QueryBuilderChipValue).rows,
      );
  }
};

interface UseFilterChipsAnalyticsArgs {
  tableId: string;
  definitions: ChipDefinition[];
  values: ChipValueMap;
  pinnedIds: string[];
}

export interface FilterChipsAnalytics {
  trackApplied: (id: string, value: ChipValue) => void;
  trackRemoved: (id: string, source: FilterRemovedSource) => void;
  trackPinned: (id: string) => void;
  trackUnpinned: (id: string) => void;
  trackManagerOpenChange: (open: boolean) => void;
}

const useFilterChipsAnalytics = ({
  tableId,
  definitions,
  values,
  pinnedIds,
}: UseFilterChipsAnalyticsArgs): FilterChipsAnalytics => {
  const pinnedDuringOpenRef = useRef(false);
  const pinnedAtStartRef = useRef(pinnedIds.length);
  const appliedCountRef = useRef(0);
  appliedCountRef.current = Object.keys(values).length;

  // Session = mount → unmount of this hook. Unmount happens when the user
  // navigates to a different table (Traces → Spans → Threads) or the tab
  // closes via SPA navigation.
  useEffect(
    () => () => {
      trackEvent(OpikEvent.FILTERS_ACTIVE_COUNT, {
        table_id: tableId,
        count: appliedCountRef.current,
      });
      trackEvent(OpikEvent.PINNED_FILTERS_COUNT, {
        table_id: tableId,
        count: pinnedAtStartRef.current,
      });
    },
    [tableId],
  );

  const track = useCallback(
    (event: OpikEventName, props?: Record<string, unknown>) => {
      trackEvent(event, { ...props, table_id: tableId });
    },
    [tableId],
  );

  const trackApplied = useCallback(
    (id: string, value: ChipValue) => {
      const def = definitions.find((d) => d.id === id);
      if (def) track(OpikEvent.FILTER_APPLIED, getFilterEventProps(def, value));
    },
    [definitions, track],
  );

  const trackRemoved = useCallback(
    (id: string, source: FilterRemovedSource) => {
      track(OpikEvent.FILTER_REMOVED, { filter_name: id, source });
    },
    [track],
  );

  const trackPinned = useCallback(
    (id: string) => {
      pinnedDuringOpenRef.current = true;
      track(OpikEvent.FILTER_PINNED, { filter_name: id });
    },
    [track],
  );

  const trackUnpinned = useCallback(
    (id: string) => {
      track(OpikEvent.FILTER_UNPINNED, { filter_name: id });
    },
    [track],
  );

  const trackManagerOpenChange = useCallback(
    (open: boolean) => {
      if (open) {
        pinnedDuringOpenRef.current = false;
        track(OpikEvent.FILTER_DIALOG_OPENED);
      } else if (!pinnedDuringOpenRef.current) {
        track(OpikEvent.FILTER_DIALOG_CLOSED_WITHOUT_SELECTION);
      }
    },
    [track],
  );

  // Stable object identity: consumers (useFilterChips' applyValue/pinChip) list
  // this in their deps, so an unmemoized object would make those callbacks —
  // and everything derived from them — change on every render.
  return useMemo(
    () => ({
      trackApplied,
      trackRemoved,
      trackPinned,
      trackUnpinned,
      trackManagerOpenChange,
    }),
    [
      trackApplied,
      trackRemoved,
      trackPinned,
      trackUnpinned,
      trackManagerOpenChange,
    ],
  );
};

export default useFilterChipsAnalytics;
