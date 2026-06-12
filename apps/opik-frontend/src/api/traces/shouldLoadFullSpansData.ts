import { COLUMN_CUSTOM_ID } from "@/types/shared";

const PAYLOAD_FIELDS = new Set(["input", "output"]);

const isPayloadFilter = (filter: unknown): boolean => {
  if (!filter || typeof filter !== "object") return false;

  const { field, key } = filter as { field?: unknown; key?: unknown };

  if (typeof field === "string" && PAYLOAD_FIELDS.has(field)) return true;

  return (
    field === COLUMN_CUSTOM_ID &&
    typeof key === "string" &&
    (key.startsWith("input.") || key.startsWith("output."))
  );
};

export default function shouldLoadFullSpansData(
  search?: unknown,
  filters?: unknown,
) {
  if (typeof search === "string" && search.trim().length > 0) return true;
  if (Array.isArray(filters) && filters.some(isPayloadFilter)) return true;

  return false;
}
