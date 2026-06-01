import { Filter } from "@/types/filters";
import { isFilterValid } from "@/lib/filters";
import {
  BooleanChipValue,
  ChipDefinition,
  ChipValueMap,
  NumericChipValue,
  PseudoSearchChipValue,
  QueryBuilderChipValue,
  SingleSelectChipValue,
  TimeChipValue,
} from "@/shared/filter-chips/types";
import { singleSelectToFilters } from "@/shared/filter-chips/chips/SingleSelectChip/SingleSelectChip.logic";
import { pseudoSearchToFilters } from "@/shared/filter-chips/chips/PseudoSearchChip/PseudoSearchChip.logic";
import { booleanToFilters } from "@/shared/filter-chips/chips/BooleanChip/BooleanChip.logic";
import { numericToFilters } from "@/shared/filter-chips/chips/NumericChip/NumericChip.logic";
import { timeToFilters } from "@/shared/filter-chips/chips/TimeChip/TimeChip.logic";
import { queryBuilderToFilters } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChip.logic";

export const chipsToFilters = (
  definitions: ChipDefinition[],
  values: ChipValueMap,
): Filter[] => {
  const result: Filter[] = [];

  for (const def of definitions) {
    const value = values[def.id];
    if (!value) continue;

    switch (def.kind) {
      case "single-select":
        result.push(
          ...singleSelectToFilters(value as SingleSelectChipValue, def),
        );
        break;
      case "pseudo-search":
        result.push(
          ...pseudoSearchToFilters(value as PseudoSearchChipValue, def),
        );
        break;
      case "boolean":
        result.push(...booleanToFilters(value as BooleanChipValue, def));
        break;
      case "numeric":
        result.push(...numericToFilters(value as NumericChipValue, def));
        break;
      case "time":
        result.push(...timeToFilters(value as TimeChipValue, def));
        break;
      case "query-builder":
        result.push(
          ...queryBuilderToFilters(value as QueryBuilderChipValue, def),
        );
        break;
    }
  }

  return result.filter(isFilterValid);
};
