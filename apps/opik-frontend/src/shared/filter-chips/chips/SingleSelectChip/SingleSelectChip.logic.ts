import partition from "lodash/partition";
import { Filter, FilterOperator } from "@/types/filters";
import {
  SingleSelectChipDefinition,
  SingleSelectChipValue,
} from "@/shared/filter-chips/types";
import {
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";

export const isSingleSelectApplied = (
  value: SingleSelectChipValue | undefined,
): boolean => {
  if (!value) return false;
  return typeof value.value === "string" && value.value !== "";
};

export const singleSelectToFilters = (
  value: SingleSelectChipValue | undefined,
  definition: SingleSelectChipDefinition,
): Filter[] => {
  if (!isSingleSelectApplied(value) || !value) return [];

  return [
    {
      id: definition.id,
      field: definition.field,
      type: definition.columnType ?? "",
      operator: definition.operator ?? "=",
      key: "",
      value: value.value,
    },
  ];
};

export const singleSelectFromFilters = (
  candidates: Filter[],
  definition: SingleSelectChipDefinition,
): FromFiltersResult<SingleSelectChipValue> => {
  const expectedOp: FilterOperator = definition.operator ?? "=";
  const [matching, wrongOp] = partition(
    candidates,
    (f) => f.operator === expectedOp,
  );
  const droppedWrongOp = dropMany(wrongOp, "unsupported_operator");

  if (matching.length === 0) {
    return { used: [], dropped: droppedWrongOp };
  }

  const allowedValues = new Set(definition.options.map((o) => o.value));
  const primary = matching.find((f) => allowedValues.has(String(f.value)));
  if (!primary) {
    return {
      used: [],
      dropped: [...droppedWrongOp, ...dropMany(matching, "invalid_value")],
    };
  }

  const [sameValue, conflict] = partition(
    matching,
    (f) => String(f.value) === String(primary.value),
  );
  return {
    value: { value: String(primary.value) },
    used: sameValue,
    dropped: [...droppedWrongOp, ...dropMany(conflict, "duplicate_field")],
  };
};

export const formatSingleSelectSummary = (
  value: SingleSelectChipValue | undefined,
  definition: SingleSelectChipDefinition,
): string | null => {
  if (!isSingleSelectApplied(value) || !value) return null;
  const option = definition.options.find((o) => o.value === value.value);
  return option?.label ?? value.value;
};
