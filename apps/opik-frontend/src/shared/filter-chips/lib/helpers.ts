import { Filter } from "@/types/filters";
import { ChipValue, QueryBuilderChipValue } from "@/shared/filter-chips/types";

export const trimValue = (raw: unknown): string => String(raw ?? "").trim();

// Safely unwraps the query-builder rows from a chip value, tolerating the
// undefined / wrong-shape cases that arise before a chip has any applied value.
export const getRows = (value: ChipValue | undefined): Filter[] => {
  if (!value || typeof value !== "object") return [];
  const candidate = (value as QueryBuilderChipValue).rows;
  return Array.isArray(candidate) ? candidate : [];
};

export const toNumber = (raw: Filter["value"]): number | null => {
  if (typeof raw === "number") return Number.isFinite(raw) ? raw : null;
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  if (s === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
};
