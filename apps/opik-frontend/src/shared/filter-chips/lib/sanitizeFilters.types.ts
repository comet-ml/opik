import { Filter } from "@/types/filters";
import { ChipValue, ChipValueMap } from "@/shared/filter-chips/types";

export type DropReason =
  | "unsupported_operator"
  | "no_matching_chip"
  | "duplicate_field"
  | "invalid_value";

export interface DroppedFilter {
  filter: Filter;
  reason: DropReason;
}

export interface FromFiltersResult<V extends ChipValue> {
  value?: V;
  used: Filter[];
  dropped: DroppedFilter[];
}

export interface SanitizeResult {
  values: ChipValueMap;
  dropped: DroppedFilter[];
}

export const drop = (filter: Filter, reason: DropReason): DroppedFilter => ({
  filter,
  reason,
});

export const dropMany = (
  filters: Filter[],
  reason: DropReason,
): DroppedFilter[] => filters.map((f) => drop(f, reason));
