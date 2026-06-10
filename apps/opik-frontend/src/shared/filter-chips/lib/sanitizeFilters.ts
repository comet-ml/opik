import groupBy from "lodash/groupBy";
import { Filter } from "@/types/filters";
import { ChipDefinition, ChipValue } from "@/shared/filter-chips/types";
import {
  drop,
  FromFiltersResult,
  SanitizeResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";
import { booleanFromFilters } from "@/shared/filter-chips/chips/BooleanChip/BooleanChip.logic";
import { singleSelectFromFilters } from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChip.logic";
import { numericFromFilters } from "@/shared/filter-chips/chips/NumericChip/NumericChip.logic";
import { timeFromFilters } from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import { queryBuilderFromFilters } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChip.logic";

export type {
  DropReason,
  DroppedFilter,
  SanitizeResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";

const fromFiltersForChip = (
  candidates: Filter[],
  def: ChipDefinition,
): FromFiltersResult<ChipValue> => {
  switch (def.kind) {
    case "boolean":
      return booleanFromFilters(candidates, def);
    case "single-select":
      return singleSelectFromFilters(candidates, def);
    case "numeric":
      return numericFromFilters(candidates);
    case "time":
      return timeFromFilters(candidates);
    case "query-builder":
      return queryBuilderFromFilters(candidates, def);
  }
};

export const sanitizeFilters = (
  filters: Filter[] | undefined | null,
  definitions: ChipDefinition[],
): SanitizeResult => {
  const result: SanitizeResult = { values: {}, dropped: [] };
  if (!Array.isArray(filters) || filters.length === 0) return result;

  const candidatesByField = groupBy(filters.filter(Boolean), (f) => f.field);
  const claimed = new Set<Filter>();

  for (const def of definitions) {
    const candidates = candidatesByField[def.field];
    if (!candidates || candidates.length === 0) continue;

    const picked = fromFiltersForChip(candidates, def);
    if (picked.value !== undefined) result.values[def.id] = picked.value;
    picked.used.forEach((f) => claimed.add(f));
    picked.dropped.forEach((entry) => {
      result.dropped.push(entry);
      claimed.add(entry.filter);
    });

    // Defensive: every candidate must end up in `used` or `dropped`. If a
    // picker branch returns without accounting for one, capture it here so
    // we never silently lose a filter — this turns picker-bug data loss
    // into an explicit drop record in the console.
    for (const candidate of candidates) {
      if (claimed.has(candidate)) continue;
      result.dropped.push(drop(candidate, "duplicate_field"));
      claimed.add(candidate);
    }
  }

  for (const f of filters) {
    if (!f || claimed.has(f)) continue;
    result.dropped.push(drop(f, "no_matching_chip"));
  }

  return result;
};
