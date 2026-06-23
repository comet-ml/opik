import { useCallback, useMemo } from "react";
import { FilterOperator } from "@/types/filters";
import { JsonValue } from "@/types/shared";
import { createFilter } from "@/lib/filters";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { ChipValue, ChipValueMap } from "@/shared/filter-chips/types";
import { getRows } from "@/shared/filter-chips/lib/helpers";
import {
  QuickAttributeFilterApi,
  QuickFilterSection,
} from "@/shared/filter-chips/QuickAttributeFilterContext";

const METADATA_CHIP_ID = "metadata";
const CUSTOM_CHIP_ID = "custom";
const PROVIDER_CHIP_ID = "provider";

// Quick-filter is a one-click flow: apply directly with "contains" (the operator
// used in the large majority of filters and valid for every target chip). The
// chip is pinned and stays editable, so users can refine the operator afterwards.
const QUICK_FILTER_OPERATOR: FilterOperator = "contains";

// "providers" (trace) is a read-time aggregate with no stored column; "provider"
// (span) is enriched into metadata but is filterable via the dedicated provider
// column. Match the root key and any array/object descendants of "providers".
const PROVIDERS_KEY = "providers";
const PROVIDER_KEY = "provider";

const isProvidersAggregateKey = (path: string): boolean =>
  path === PROVIDERS_KEY ||
  path.startsWith(`${PROVIDERS_KEY}[`) ||
  path.startsWith(`${PROVIDERS_KEY}.`);

const isProviderRootKey = (path: string): boolean => path === PROVIDER_KEY;

interface UseQuickAttributeFilterActionsArgs {
  type: TRACE_DATA_TYPE;
  values: ChipValueMap;
  applyValue: (id: string, value: ChipValue) => void;
  pinChip: (id: string) => void;
}

export const stringifyFilterValue = (value: JsonValue): string => {
  if (value === null) return "";
  if (typeof value === "string") return value;
  return String(value);
};

type FilterTarget = { chipId: string; key?: string };

// Resolves which chip/field a quick-filter targets. Returns null when the
// attribute can't be filtered for the active tab (caller hides the action).
export const resolveQuickFilterTarget = (
  section: QuickFilterSection,
  type: TRACE_DATA_TYPE,
  path: string,
): FilterTarget | null => {
  if (section === "metadata") {
    if (isProvidersAggregateKey(path)) return null;
    if (isProviderRootKey(path)) {
      // Spans store provider in a dedicated column; traces have no such field.
      return type === TRACE_DATA_TYPE.spans
        ? { chipId: PROVIDER_CHIP_ID }
        : null;
    }
    return { chipId: METADATA_CHIP_ID, key: path };
  }
  // input / output map to the custom filter, which keeps the root prefix.
  return { chipId: CUSTOM_CHIP_ID, key: `${section}.${path}` };
};

export const useQuickAttributeFilterActions = ({
  type,
  values,
  applyValue,
  pinChip,
}: UseQuickAttributeFilterActionsArgs): QuickAttributeFilterApi => {
  const canFilter = useCallback(
    (section: QuickFilterSection, path: string) =>
      Boolean(path) && resolveQuickFilterTarget(section, type, path) !== null,
    [type],
  );

  // One-click apply: add a "contains" row for the clicked attribute and pin the
  // chip (which stays open for refinement). No confirmation dialog.
  const filter = useCallback(
    (section: QuickFilterSection, path: string, value: JsonValue) => {
      const target = resolveQuickFilterTarget(section, type, path);
      if (!target) return;

      const { chipId, key } = target;
      const stringValue = stringifyFilterValue(value);

      const existing = getRows(values[chipId]);
      const alreadyApplied = existing.some(
        (row) =>
          (row.key ?? "") === (key ?? "") &&
          row.operator === QUICK_FILTER_OPERATOR &&
          String(row.value) === stringValue,
      );

      if (!alreadyApplied) {
        const nextRow = createFilter({
          ...(key !== undefined ? { key } : {}),
          operator: QUICK_FILTER_OPERATOR,
          value: stringValue,
        });
        // Drop blank rows but keep any real rows the user already added.
        const nextRows = existing.filter(
          (row) => (row.key ?? "") !== "" || (row.value ?? "") !== "",
        );
        applyValue(chipId, { rows: [...nextRows, nextRow] });
      }

      pinChip(chipId);

      // Usage signal: which tab (traces/spans) and Inspect section the
      // quick-filter was applied from.
      trackEvent(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: type,
        source: section,
        filter_name: chipId,
        operator: QUICK_FILTER_OPERATOR,
      });
    },
    [type, values, applyValue, pinChip],
  );

  return useMemo(() => ({ canFilter, filter }), [canFilter, filter]);
};
