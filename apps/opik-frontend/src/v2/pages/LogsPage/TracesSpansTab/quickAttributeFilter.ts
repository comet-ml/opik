import { Filter, FilterOperator } from "@/types/filters";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  JsonValue,
} from "@/types/shared";
import { createFilter } from "@/lib/filters";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { QuickFilterSection } from "@/shared/filter-chips/QuickAttributeFilterContext";

// "contains" is valid for every target chip; the pinned chip stays editable.
export const QUICK_FILTER_OPERATOR: FilterOperator = "contains";

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

const METADATA_TARGET = {
  chipId: "metadata",
  field: COLUMN_METADATA_ID,
  columnType: COLUMN_TYPE.dictionary,
};
const CUSTOM_TARGET = {
  chipId: "custom",
  field: COLUMN_CUSTOM_ID,
  columnType: COLUMN_TYPE.dictionary,
};
const PROVIDER_TARGET = {
  chipId: "provider",
  field: "provider",
  columnType: COLUMN_TYPE.string,
};

export type QuickFilterTarget = {
  chipId: string;
  field: string;
  columnType: COLUMN_TYPE;
  key?: string;
};

export const stringifyFilterValue = (value: JsonValue): string => {
  if (value === null) return "";
  if (typeof value === "string") return value;
  return String(value);
};

// Resolves which chip/field a quick-filter targets. Returns null when the
// attribute can't be filtered (caller hides the action).
export const resolveQuickFilterTarget = (
  section: QuickFilterSection,
  type: TRACE_DATA_TYPE,
  path: string,
): QuickFilterTarget | null => {
  if (!path) return null;
  if (section === "metadata") {
    if (isProvidersAggregateKey(path)) return null;
    if (isProviderRootKey(path)) {
      // Spans store provider in a dedicated column; traces have no such field.
      return type === TRACE_DATA_TYPE.spans ? PROVIDER_TARGET : null;
    }
    return { ...METADATA_TARGET, key: path };
  }
  // input / output map to the custom filter, which keeps the root prefix.
  return { ...CUSTOM_TARGET, key: `${section}.${path}` };
};

export const addQuickFilter = (
  filters: Filter[],
  target: QuickFilterTarget,
  value: string,
): Filter[] => {
  const key = target.key ?? "";
  const validFilters = filters.filter(Boolean);
  const alreadyApplied = validFilters.some(
    (filter) =>
      filter.field === target.field &&
      (filter.key ?? "") === key &&
      filter.operator === QUICK_FILTER_OPERATOR &&
      String(filter.value) === value,
  );
  if (alreadyApplied) return validFilters;

  return [
    ...validFilters,
    createFilter({
      field: target.field,
      type: target.columnType,
      key,
      operator: QUICK_FILTER_OPERATOR,
      value,
    }),
  ];
};
