import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, NumberParam, useQueryParam } from "use-query-params";
import { Filter } from "@/types/filters";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import { ChipValue, ChipValueMap } from "@/shared/filter-chips/types";
import { QuickAttributeFilterApi } from "@/shared/filter-chips/QuickAttributeFilterContext";
import { useQuickAttributeFilterActions } from "@/shared/filter-chips/hooks/useQuickAttributeFilterActions";
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
        const existing = Array.isArray(prevRaw) ? prevRaw : [];
        const duplicate = existing.some(
          (r) =>
            r.field === row.field &&
            (r.key ?? "") === (row.key ?? "") &&
            r.operator === row.operator &&
            String(r.value) === String(row.value),
        );
        return duplicate ? prevRaw : [...existing, row];
      });
      setPinnedIds((prev = view.defaultPinned) =>
        prev.includes(chipId) ? prev : [...prev, chipId],
      );
    },
    [view, setRawFilters, setPinnedIds],
  );
};

interface UseLogsQuickAttributeFilterArgs {
  logsType: LOGS_TYPE;
  spanId: string | null | undefined;
  onLogsTypeChange: (logsType: LOGS_TYPE) => void;
  // The mounted view's own chips. Omitted by Threads, whose table has no
  // metadata / input / output fields and so can never hold these filters.
  values?: ChipValueMap;
  applyValue?: (id: string, value: ChipValue) => void;
  pinChip?: (id: string) => void;
}

/**
 * The quick-filter behaviour for every Logs view, in one place.
 *
 * The attributes in the details panel belong to the selected span, or to the
 * trace when no span is selected. That entity's view owns the filter: if it is
 * already on screen the filter applies in place, otherwise it is written there
 * and the table follows.
 */
const useLogsQuickAttributeFilter = ({
  logsType,
  spanId,
  onLogsTypeChange,
  values,
  applyValue,
  pinChip,
}: UseLogsQuickAttributeFilterArgs): QuickAttributeFilterApi => {
  const applyToTraces = useHandoffWriter(TRACES_VIEW);
  const applyToSpans = useHandoffWriter(SPANS_VIEW);
  const [, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const target = spanId ? SPANS_VIEW : TRACES_VIEW;
  const needsHandoff = logsType !== target.logsType;

  const handoff = useCallback(
    (chipId: string, row: Filter) => {
      (target === SPANS_VIEW ? applyToSpans : applyToTraces)(chipId, row);
      // The destination shows a fresh result set, so the page the user was on
      // would land them past the end of it.
      setPage(1);
      onLogsTypeChange(target.logsType);
    },
    [target, applyToSpans, applyToTraces, setPage, onLogsTypeChange],
  );

  return useQuickAttributeFilterActions({
    type: target.type,
    tableId: target.tableId,
    values,
    applyValue,
    pinChip,
    handoff: needsHandoff ? handoff : undefined,
  });
};

export default useLogsQuickAttributeFilter;
