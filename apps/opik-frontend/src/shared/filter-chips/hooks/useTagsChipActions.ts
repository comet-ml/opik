import { useCallback } from "react";
import { Filter, FilterOperator } from "@/types/filters";
import { createFilter } from "@/lib/filters";
import {
  ChipValue,
  ChipValueMap,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";

interface UseTagsChipActionsArgs {
  chipId: string;
  values: ChipValueMap;
  applyValue: (id: string, value: ChipValue) => void;
  pinChip: (id: string) => void;
  defaultOperator?: FilterOperator;
}

interface TagsChipActions {
  addTag: (tag: string) => void;
}

const getRows = (value: ChipValue | undefined): Filter[] => {
  if (!value || typeof value !== "object") return [];
  const candidate = (value as QueryBuilderChipValue).rows;
  return Array.isArray(candidate) ? candidate : [];
};

export const useTagsChipActions = ({
  chipId,
  values,
  applyValue,
  pinChip,
  defaultOperator = "contains",
}: UseTagsChipActionsArgs): TagsChipActions => {
  const addTag = useCallback(
    (tag: string) => {
      const trimmed = tag.trim();
      if (trimmed === "") return;

      const existing = getRows(values[chipId]);
      const alreadyApplied = existing.some(
        (row) => row.operator === defaultOperator && row.value === trimmed,
      );
      if (alreadyApplied) {
        pinChip(chipId);
        return;
      }

      const nextRow = createFilter({
        operator: defaultOperator,
        value: trimmed,
      });
      const nextRows = existing.filter(
        (row) => row.value !== "" && row.value !== undefined,
      );
      applyValue(chipId, { rows: [...nextRows, nextRow] });
      pinChip(chipId);
    },
    [chipId, values, applyValue, pinChip, defaultOperator],
  );

  return { addTag };
};
