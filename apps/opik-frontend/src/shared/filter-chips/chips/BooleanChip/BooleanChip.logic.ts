import partition from "lodash/partition";
import { Filter } from "@/types/filters";
import {
  BooleanChipDefinition,
  BooleanChipValue,
} from "@/shared/filter-chips/types";
import {
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";

export const isBooleanApplied = (
  value: BooleanChipValue | undefined,
): boolean => Boolean(value?.applied);

export const booleanToFilters = (
  value: BooleanChipValue | undefined,
  definition: BooleanChipDefinition,
): Filter[] => {
  if (!isBooleanApplied(value)) return [];

  return [
    {
      id: definition.id,
      field: definition.field,
      type: definition.columnType ?? "",
      operator: definition.onOperator,
      key: "",
      value: definition.onValue ?? "",
    },
  ];
};

export const booleanFromFilters = (
  candidates: Filter[],
  definition: BooleanChipDefinition,
): FromFiltersResult<BooleanChipValue> => {
  const [matching, others] = partition(
    candidates,
    (f) => f.operator === definition.onOperator,
  );
  const dropped = dropMany(others, "unsupported_operator");

  if (matching.length === 0) {
    return { used: [], dropped };
  }
  return { value: { applied: true }, used: matching, dropped };
};
