import { isPayloadFilter } from "@/api/traces/payloadFilter";

export default function shouldLoadFullSpansData(
  search?: unknown,
  filters?: unknown,
) {
  if (typeof search === "string" && search.trim().length > 0) return true;
  if (Array.isArray(filters) && filters.some(isPayloadFilter)) return true;

  return false;
}
