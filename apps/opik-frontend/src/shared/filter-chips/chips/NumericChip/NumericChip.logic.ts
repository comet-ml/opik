import groupBy from "lodash/groupBy";
import maxBy from "lodash/maxBy";
import minBy from "lodash/minBy";
import partition from "lodash/partition";
import { Filter, FilterOperator } from "@/types/filters";
import {
  NumericChipDefinition,
  NumericChipValue,
} from "@/shared/filter-chips/types";
import {
  formatNumericValue,
  resolveNumericFormat,
} from "@/shared/filter-chips/chips/NumericChip/NumericChip.format";
import {
  drop,
  DroppedFilter,
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";
import { toNumber } from "@/shared/filter-chips/lib/helpers";

const SUPPORTED_OPERATORS: ReadonlySet<FilterOperator> = new Set([
  "=",
  ">",
  ">=",
  "<",
  "<=",
]);

const tightest = (
  rows: Filter[],
  pick: "max" | "min",
  dropped: DroppedFilter[],
): { value: number; used: Filter[] } | null => {
  const [parsed, invalid] = partition(rows, (r) => toNumber(r.value) !== null);
  invalid.forEach((f) => dropped.push(drop(f, "invalid_value")));
  if (parsed.length === 0) return null;
  const chosen =
    pick === "max"
      ? maxBy(parsed, (f) => toNumber(f.value)!)
      : minBy(parsed, (f) => toNumber(f.value)!);
  if (!chosen) return null;
  return { value: toNumber(chosen.value)!, used: parsed };
};

export const isNumericApplied = (
  value: NumericChipValue | undefined,
): boolean => {
  if (!value) return false;
  switch (value.mode) {
    case "exactly":
      return Number.isFinite(value.exact);
    case "atLeast":
      return Number.isFinite(value.min);
    case "atMost":
      return Number.isFinite(value.max);
    case "between":
      return (
        Number.isFinite(value.min) &&
        Number.isFinite(value.max) &&
        value.min <= value.max
      );
  }
};

export const numericToFilters = (
  value: NumericChipValue | undefined,
  definition: NumericChipDefinition,
): Filter[] => {
  if (!isNumericApplied(value) || !value) return [];

  const field = definition.field;
  const type: Filter["type"] = definition.columnType ?? "";
  const key = "";
  const base = { field, type, key };
  const idMin = `${definition.id}_min`;
  const idMax = `${definition.id}_max`;

  switch (value.mode) {
    case "exactly":
      return [
        {
          id: definition.id,
          ...base,
          operator: "=",
          value: String(value.exact),
        },
      ];
    case "atLeast":
      return [{ id: idMin, ...base, operator: ">=", value: String(value.min) }];
    case "atMost":
      return [{ id: idMax, ...base, operator: "<=", value: String(value.max) }];
    case "between":
      return [
        { id: idMin, ...base, operator: ">=", value: String(value.min) },
        { id: idMax, ...base, operator: "<=", value: String(value.max) },
      ];
  }
};

export const numericFromFilters = (
  candidates: Filter[],
): FromFiltersResult<NumericChipValue> => {
  const [supported, unsupported] = partition(candidates, (f) =>
    SUPPORTED_OPERATORS.has(f.operator as FilterOperator),
  );
  const dropped: DroppedFilter[] = dropMany(
    unsupported,
    "unsupported_operator",
  );
  const byOp = groupBy(supported, "operator");
  const gte = [...(byOp[">="] ?? []), ...(byOp[">"] ?? [])];
  const lte = [...(byOp["<="] ?? []), ...(byOp["<"] ?? [])];
  const eq = byOp["="] ?? [];

  // Prefer between when both bounds are present.
  if (gte.length > 0 && lte.length > 0) {
    const lower = tightest(gte, "max", dropped);
    const upper = tightest(lte, "min", dropped);
    if (lower && upper) {
      if (lower.value <= upper.value) {
        return {
          value: { mode: "between", min: lower.value, max: upper.value },
          used: [...lower.used, ...upper.used],
          dropped: [...dropped, ...dropMany(eq, "duplicate_field")],
        };
      }
      // Unsatisfiable range: lower > upper means no value matches both
      // bounds. Reachable from typo'd / shared URLs and from multiple
      // overlapping bounds where tightest(gte)=max and tightest(lte)=min
      // collapse to an empty set. Drop both as invalid_value rather than
      // silently demoting to atLeast and losing the upper bound.
      return {
        used: [],
        dropped: [
          ...dropped,
          ...dropMany([...lower.used, ...upper.used], "invalid_value"),
          ...dropMany(eq, "duplicate_field"),
        ],
      };
    }
  }

  if (eq.length > 0) {
    const [first, ...rest] = eq;
    const n = toNumber(first.value);
    if (n !== null) {
      const [sameValue, conflict] = partition(
        rest,
        (f) => toNumber(f.value) === n,
      );
      return {
        value: { mode: "exactly", exact: n },
        used: [first, ...sameValue],
        dropped: [
          ...dropped,
          ...dropMany(conflict, "duplicate_field"),
          ...dropMany([...gte, ...lte], "duplicate_field"),
        ],
      };
    }
    dropped.push(drop(first, "invalid_value"));
  }

  if (gte.length > 0) {
    const lower = tightest(gte, "max", dropped);
    if (lower) {
      return {
        value: { mode: "atLeast", min: lower.value },
        used: lower.used,
        dropped: [...dropped, ...dropMany(lte, "duplicate_field")],
      };
    }
  }

  if (lte.length > 0) {
    const upper = tightest(lte, "min", dropped);
    if (upper) {
      return {
        value: { mode: "atMost", max: upper.value },
        used: upper.used,
        dropped,
      };
    }
  }

  return { used: [], dropped };
};

export const formatNumericSummary = (
  value: NumericChipValue | undefined,
  definition: NumericChipDefinition,
): string | null => {
  if (!isNumericApplied(value) || !value) return null;
  const format = resolveNumericFormat(definition);
  const f = (n: number) => formatNumericValue(n, format);
  switch (value.mode) {
    case "exactly":
      return `= ${f(value.exact)}`;
    case "atLeast":
      return `≥ ${f(value.min)}`;
    case "atMost":
      return `≤ ${f(value.max)}`;
    case "between":
      return `${f(value.min)} – ${f(value.max)}`;
  }
};
