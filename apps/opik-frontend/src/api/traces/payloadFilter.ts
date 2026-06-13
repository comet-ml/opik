import { Filter } from "@/types/filters";
import { COLUMN_CUSTOM_ID, COLUMN_TYPE } from "@/types/shared";

const PAYLOAD_FIELDS = new Set(["input", "output"]);
const PAYLOAD_PREFIXES = [
  { fieldName: "input" as const, prefix: "input." },
  { fieldName: "output" as const, prefix: "output." },
];

type PayloadFilterLike = {
  field?: unknown;
  key?: unknown;
};

export const isPayloadFilter = (filter: unknown): boolean => {
  if (!filter || typeof filter !== "object") return false;

  const normalizedFilter = normalizePayloadFilter(filter as PayloadFilterLike);

  return (
    typeof normalizedFilter.field === "string" &&
    PAYLOAD_FIELDS.has(normalizedFilter.field)
  );
};

export const normalizePayloadFilter = <T extends PayloadFilterLike>(
  filter: T,
): T => {
  if (filter.field !== COLUMN_CUSTOM_ID || typeof filter.key !== "string") {
    return filter;
  }

  for (const { fieldName, prefix } of PAYLOAD_PREFIXES) {
    if (filter.key.startsWith(prefix)) {
      return {
        ...filter,
        field: fieldName,
        key: filter.key.substring(prefix.length),
        type: COLUMN_TYPE.dictionary,
      } as T;
    }
  }

  return filter;
};

export const normalizeTraceTreeFilter = (filter: Filter): Filter =>
  normalizePayloadFilter(filter);
