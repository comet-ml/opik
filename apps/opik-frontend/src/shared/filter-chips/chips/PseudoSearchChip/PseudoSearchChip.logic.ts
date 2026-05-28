import partition from "lodash/partition";
import { Filter, FilterOperator } from "@/types/filters";
import {
  PseudoSearchChipDefinition,
  PseudoSearchChipValue,
} from "@/shared/filter-chips/types";
import {
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";
import { trimValue } from "@/shared/filter-chips/lib/helpers";

export const isPseudoSearchApplied = (
  value: PseudoSearchChipValue | undefined,
): boolean => trimValue(value?.value) !== "";

export const pseudoSearchToFilters = (
  value: PseudoSearchChipValue | undefined,
  definition: PseudoSearchChipDefinition,
): Filter[] => {
  if (!isPseudoSearchApplied(value) || !value) return [];

  const trimmed = trimValue(value.value);
  return [
    {
      id: definition.id,
      field: definition.field,
      type: definition.columnType ?? "",
      operator: definition.searchMode === "equals" ? "=" : "contains",
      key: "",
      value: trimmed,
    },
  ];
};

export const pseudoSearchFromFilters = (
  candidates: Filter[],
  definition: PseudoSearchChipDefinition,
): FromFiltersResult<PseudoSearchChipValue> => {
  const expectedOp: FilterOperator =
    definition.searchMode === "equals" ? "=" : "contains";
  const [matching, wrongOp] = partition(
    candidates,
    (f) => f.operator === expectedOp,
  );
  const droppedWrongOp = dropMany(wrongOp, "unsupported_operator");

  if (matching.length === 0) {
    return { used: [], dropped: droppedWrongOp };
  }

  const primary = matching.find((f) => trimValue(f.value) !== "");
  if (!primary) {
    return {
      used: [],
      dropped: [...droppedWrongOp, ...dropMany(matching, "invalid_value")],
    };
  }
  const value = trimValue(primary.value);

  const [sameValue, conflict] = partition(
    matching,
    (f) => trimValue(f.value) === value,
  );
  return {
    value: { value },
    used: sameValue,
    dropped: [...droppedWrongOp, ...dropMany(conflict, "duplicate_field")],
  };
};

export const formatPseudoSearchSummary = (
  value: PseudoSearchChipValue | undefined,
  definition: PseudoSearchChipDefinition,
): string | null => {
  if (!isPseudoSearchApplied(value) || !value) return null;
  const trimmed = trimValue(value.value);
  if (definition.searchMode === "equals" && trimmed.length > 3) {
    return `...${trimmed.slice(-3)}`;
  }
  return trimmed;
};
