import { useCallback, useMemo, useRef } from "react";
import { Filter, FilterOperator } from "@/types/filters";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  JsonValue,
} from "@/types/shared";
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
const PROVIDER_FIELD = "provider";

// "contains" is valid for every target chip; the pinned chip stays editable.
const QUICK_FILTER_OPERATOR: FilterOperator = "contains";

// The hint always names the table the filter lands on, because the same icon
// sits on trace attributes and on span attributes. The confirmation only calls
// out a destination when the click also moves the table there.
const HINT_TEXT_TRACES = "Filter traces by this attribute";
const HINT_TEXT_SPANS = "Filter spans by this attribute";
const APPLIED_TEXT = "Filter applied";
const APPLIED_TEXT_TRACES = "Filter applied to Traces";
const APPLIED_TEXT_SPANS = "Filter applied to Spans";

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

// The mounted chip state of the view named by `type`. All three come together:
// reading the current rows, writing them back and pinning the chip are one
// operation, so a caller cannot hold half of it.
export interface QuickFilterLocalChips {
  values: ChipValueMap;
  applyValue: (id: string, value: ChipValue) => void;
  pinChip: (id: string) => void;
}

interface UseQuickAttributeFilterActionsArgs {
  // The entity whose attributes are on screen. It is also the view the filter
  // lands on, so it drives the target chip, the labels and the analytics.
  type: TRACE_DATA_TYPE;
  tableId: string;
  // Omitted by callers whose own table can never hold the filter (Threads).
  local?: QuickFilterLocalChips;
  // Set when that view is not the one on screen: the row is sent there and the
  // caller moves the table, instead of applying the filter in place.
  handoff?: (chipId: string, row: Filter) => void;
}

export const stringifyFilterValue = (value: JsonValue): string => {
  if (value === null) return "";
  if (typeof value === "string") return value;
  return String(value);
};

// The chip carries `field` and `columnType`, but a handoff writes into a view
// whose chip definitions are not built here. Carrying them on the target keeps
// the handoff free of definitions, so it never has to re-derive another view's
// filters (and cannot drop the ones it does not know about).
type FilterTarget = {
  chipId: string;
  field: string;
  columnType: COLUMN_TYPE;
  key?: string;
};

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
        ? {
            chipId: PROVIDER_CHIP_ID,
            field: PROVIDER_FIELD,
            columnType: COLUMN_TYPE.string,
          }
        : null;
    }
    return {
      chipId: METADATA_CHIP_ID,
      field: COLUMN_METADATA_ID,
      columnType: COLUMN_TYPE.dictionary,
      key: path,
    };
  }
  // input / output map to the custom filter, which keeps the root prefix.
  return {
    chipId: CUSTOM_CHIP_ID,
    field: COLUMN_CUSTOM_ID,
    columnType: COLUMN_TYPE.dictionary,
    key: `${section}.${path}`,
  };
};

export const useQuickAttributeFilterActions = ({
  type,
  tableId,
  local,
  handoff,
}: UseQuickAttributeFilterActionsArgs): QuickAttributeFilterApi => {
  // Read the mounted chips through a ref so applying a filter (which mutates
  // `values`) doesn't change `filter`'s identity — otherwise every chip edit
  // would tear down and rebuild the CodeMirror quick-filter extension.
  const localRef = useRef(local);
  localRef.current = local;

  const canFilter = useCallback(
    (section: QuickFilterSection, path: string) =>
      Boolean(path) && resolveQuickFilterTarget(section, type, path) !== null,
    [type],
  );

  const filter = useCallback(
    (section: QuickFilterSection, path: string, value: JsonValue) => {
      const target = resolveQuickFilterTarget(section, type, path);
      if (!target) return;

      const { chipId, field, columnType, key } = target;
      const stringValue = stringifyFilterValue(value);
      // `field`/`type` are redundant for a local apply (the chip definition
      // overwrites them), but a handoff has no definitions to fill them in.
      const buildRow = () =>
        createFilter({
          ...(key !== undefined ? { key } : {}),
          field,
          type: columnType,
          operator: QUICK_FILTER_OPERATOR,
          value: stringValue,
        });

      if (handoff) {
        // The destination view owns its chip state, so it merges and pins.
        handoff(chipId, buildRow());
      } else {
        const chips = localRef.current;
        // No handoff and no mounted chips: nothing can hold the filter.
        if (!chips) return;

        const existing = getRows(chips.values[chipId]);
        const alreadyApplied = existing.some(
          (row) =>
            (row.key ?? "") === (key ?? "") &&
            row.operator === QUICK_FILTER_OPERATOR &&
            String(row.value) === stringValue,
        );

        if (!alreadyApplied) {
          // Drop blank rows but keep any real rows the user already added.
          const nextRows = existing.filter(
            (row) => (row.key ?? "") !== "" || (row.value ?? "") !== "",
          );
          chips.applyValue(chipId, { rows: [...nextRows, buildRow()] });
        }

        chips.pinChip(chipId);
      }

      trackEvent(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: type,
        source: section,
        filter_name: chipId,
        operator: QUICK_FILTER_OPERATOR,
        table_id: tableId,
      });
    },
    [type, tableId, handoff],
  );

  const targetsSpans = type === TRACE_DATA_TYPE.spans;

  return useMemo(
    () => ({
      canFilter,
      filter,
      hintText: targetsSpans ? HINT_TEXT_SPANS : HINT_TEXT_TRACES,
      appliedText: !handoff
        ? APPLIED_TEXT
        : targetsSpans
          ? APPLIED_TEXT_SPANS
          : APPLIED_TEXT_TRACES,
    }),
    [canFilter, filter, targetsSpans, handoff],
  );
};
