import partition from "lodash/partition";
import uniqid from "uniqid";
import { Filter, FilterOperator } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import { truncateMiddle } from "@/lib/utils";
import { NO_VALUE_OPERATORS } from "@/constants/filters";
import {
  QueryBuilderChipDefinition,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";
import {
  getOperatorLabel,
  getOperatorShortLabel,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";
import {
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";

const KEY_BUDGET = 18;
const VALUE_BUDGET = 18;

export interface QueryBuilderSummary {
  display: string;
  tooltip: string;
}

export const isQueryBuilderApplied = (
  value: QueryBuilderChipValue | undefined,
): boolean => {
  if (!value || !Array.isArray(value.rows)) return false;
  return value.rows.some(isFilterValid);
};

export const queryBuilderToFilters = (
  value: QueryBuilderChipValue | undefined,
  definition: QueryBuilderChipDefinition,
): Filter[] => {
  if (!isQueryBuilderApplied(value) || !value) return [];

  return value.rows.filter(isFilterValid).map((row) => ({
    ...row,
    id: row.id || uniqid(),
    field: definition.field,
    type: definition.columnType,
  }));
};

// Silent lossy demote: strict bounds become inclusive when the chip's
// allow-list exposes only the inclusive form. Mirrors NumericChip's
// `>` → `>=` behaviour so legacy URLs with strict bounds (e.g. feedback
// scores from v1) survive sanitization instead of being dropped.
const demoteOperator = (
  op: FilterOperator,
  allowed: Set<FilterOperator>,
): FilterOperator => {
  if (allowed.has(op)) return op;
  if (op === ">" && allowed.has(">=")) return ">=";
  if (op === "<" && allowed.has("<=")) return "<=";
  return op;
};

export const queryBuilderFromFilters = (
  candidates: Filter[],
  definition: QueryBuilderChipDefinition,
): FromFiltersResult<QueryBuilderChipValue> => {
  const allowed = new Set<FilterOperator>(definition.operators);

  const normalised = candidates.map((source) => ({
    source,
    row: {
      ...source,
      operator: demoteOperator(source.operator as FilterOperator, allowed),
      field: definition.field,
      type: definition.columnType,
    },
  }));

  const [withAllowedOp, withDisallowedOp] = partition(normalised, (n) =>
    allowed.has(n.row.operator as FilterOperator),
  );
  const droppedOp = dropMany(
    withDisallowedOp.map((n) => n.source),
    "unsupported_operator",
  );

  const [valid, invalid] = partition(withAllowedOp, (n) =>
    isFilterValid(n.row),
  );
  const droppedInvalid = invalid.map((n) => ({
    filter: n.source,
    reason: "invalid_value" as const,
  }));

  if (valid.length === 0) {
    return { used: [], dropped: [...droppedOp, ...droppedInvalid] };
  }
  return {
    value: { rows: valid.map((n) => n.row) },
    used: valid.map((n) => n.source),
    dropped: [...droppedOp, ...droppedInvalid],
  };
};

const trimKey = (key: string, max: number): string => {
  if (key.length <= max) return key;
  const parts = key.split(".");
  if (parts.length > 1) {
    const candidate = `${parts[0]}.…${parts[parts.length - 1]}`;
    if (candidate.length <= max) return candidate;
  }
  return truncateMiddle(key, max);
};

const composeRow = (
  row: Filter,
  hasKey: boolean,
  opLabel: (op: FilterOperator) => string,
  trimmed: boolean,
): string => {
  const op = row.operator as FilterOperator;
  const omitValue = NO_VALUE_OPERATORS.includes(op);
  const keyRaw = hasKey ? (row.key ?? "").trim() : "";
  const valueRaw = omitValue ? "" : String(row.value ?? "").trim();
  const keyText = trimmed ? trimKey(keyRaw, KEY_BUDGET) : keyRaw;
  const valueText = trimmed ? truncateMiddle(valueRaw, VALUE_BUDGET) : valueRaw;
  return [keyText, opLabel(op), valueText].filter((p) => p !== "").join(" ");
};

export const formatQueryBuilderSummary = (
  value: QueryBuilderChipValue | undefined,
  definition: QueryBuilderChipDefinition,
): QueryBuilderSummary | null => {
  if (!isQueryBuilderApplied(value) || !value) return null;
  const valid = value.rows.filter(isFilterValid);
  if (valid.length === 0) return null;

  const hasKey = Boolean(definition.key);
  if (valid.length > 1) {
    return {
      display: String(valid.length),
      tooltip: valid
        .map((row) => composeRow(row, hasKey, getOperatorLabel, false))
        .join("\nAND: "),
    };
  }

  const [row] = valid;
  return {
    display: composeRow(row, hasKey, getOperatorShortLabel, true),
    tooltip: composeRow(row, hasKey, getOperatorLabel, false),
  };
};
