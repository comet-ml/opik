import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { Filter } from "@/types/filters";
import { JsonValue } from "@/types/shared";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  QuickAttributeFilterApi,
  QuickFilterSection,
} from "@/shared/filter-chips/QuickAttributeFilterContext";
import {
  QUICK_FILTER_OPERATOR,
  addQuickFilter,
  resolveQuickFilterTarget,
  stringifyFilterValue,
} from "@/v2/pages/LogsPage/TracesSpansTab/quickAttributeFilter";
import { getPinnedChipsStorageKey } from "@/shared/filter-chips/hooks/useFilterChips";
import {
  LOGS_DEFAULT_PINNED_CHIPS,
  LOGS_TABLE_ID,
  getLogsFiltersMemoryKey,
  getLogsFiltersUrlKey,
} from "@/v2/pages/LogsPage/TracesSpansTab/constants";

const NO_FILTERS: Filter[] = [];

const LOGS_TYPE_BY_DATA_TYPE: Record<TRACE_DATA_TYPE, LOGS_TYPE> = {
  [TRACE_DATA_TYPE.traces]: LOGS_TYPE.traces,
  [TRACE_DATA_TYPE.spans]: LOGS_TYPE.spans,
};

const REDIRECT_HINT: Record<TRACE_DATA_TYPE, string> = {
  [TRACE_DATA_TYPE.traces]: "Filter in Traces table",
  [TRACE_DATA_TYPE.spans]: "Filter in Spans table",
};

const usePinChip = (type: TRACE_DATA_TYPE) => {
  const [, setPinnedIds] = useLocalStorageState<string[]>(
    getPinnedChipsStorageKey(LOGS_TABLE_ID[type]),
    { defaultValue: LOGS_DEFAULT_PINNED_CHIPS[type] },
  );

  return useCallback(
    (chipId: string) =>
      setPinnedIds((prev = LOGS_DEFAULT_PINNED_CHIPS[type]) =>
        prev.includes(chipId) ? prev : [...prev, chipId],
      ),
    [setPinnedIds, type],
  );
};

type UseLogsQuickAttributeFilterArgs = {
  type: TRACE_DATA_TYPE;
  projectId: string;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
};

export const useLogsQuickAttributeFilter = ({
  type,
  projectId,
  onLogsTypeChange,
}: UseLogsQuickAttributeFilterArgs): QuickAttributeFilterApi => {
  const [spanId] = useQueryParam("span", StringParam);
  const entityType = spanId ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces;
  const filtersUrlKey = getLogsFiltersUrlKey(entityType);

  const [, setFilters] = useQueryParam<Filter[] | undefined>(
    filtersUrlKey,
    JsonParam,
    { updateType: "replaceIn" },
  );
  const [saved, setSaved] = useLocalStorageState<unknown>(
    getLogsFiltersMemoryKey(projectId, filtersUrlKey),
    { storageSync: false },
  );
  const savedFilters = Array.isArray(saved) ? (saved as Filter[]) : NO_FILTERS;
  const pinTraceChip = usePinChip(TRACE_DATA_TYPE.traces);
  const pinSpanChip = usePinChip(TRACE_DATA_TYPE.spans);

  const canFilter = useCallback(
    (section: QuickFilterSection, path: string) =>
      resolveQuickFilterTarget(section, entityType, path) !== null,
    [entityType],
  );

  const filter = useCallback(
    (section: QuickFilterSection, path: string, value: JsonValue) => {
      const target = resolveQuickFilterTarget(section, entityType, path);
      if (!target) return;

      setFilters((current) => {
        // An absent param (bare landing) still has remembered filters to build on.
        const next = addQuickFilter(
          Array.isArray(current) ? current : savedFilters,
          target,
          stringifyFilterValue(value),
        );
        setSaved(next);
        return next;
      });

      if (entityType === TRACE_DATA_TYPE.spans) {
        pinSpanChip(target.chipId);
      } else {
        pinTraceChip(target.chipId);
      }

      if (entityType !== type) {
        onLogsTypeChange(LOGS_TYPE_BY_DATA_TYPE[entityType]);
      }

      trackEvent(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: entityType,
        source: section,
        filter_name: target.chipId,
        operator: QUICK_FILTER_OPERATOR,
        table_id: LOGS_TABLE_ID[entityType],
      });
    },
    [
      entityType,
      type,
      setFilters,
      savedFilters,
      setSaved,
      pinSpanChip,
      pinTraceChip,
      onLogsTypeChange,
    ],
  );

  const hint = entityType === type ? undefined : REDIRECT_HINT[entityType];

  return useMemo(
    () => ({ canFilter, filter, hint }),
    [canFilter, filter, hint],
  );
};
