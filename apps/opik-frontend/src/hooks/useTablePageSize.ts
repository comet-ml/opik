import { NumberParam, useQueryParam } from "use-query-params";
import { useDefaultPageSize } from "@/contexts/feature-toggles-provider";
import { OnChangeFn } from "@/types/shared";

const DEFAULT_QUERY_KEY = "size";

// Shared page-size wiring for v1/v2 Experiments tables (and any table that
// reads pagination size from the URL). Precedence:
//   1. A valid positive integer in the URL query param always wins - users
//      must be able to pick any dropdown value and share the link.
//   2. Otherwise the deployment default from ServiceTogglesConfig.defaultPageSize
//      (exposed by FeatureTogglesProvider as useDefaultPageSize()).
//
// Guards against NaN / 0 / negative / fractional URL input so malformed
// ?size= values can't silently bypass the deployment default.
export default function useTablePageSize(
  queryKey: string = DEFAULT_QUERY_KEY,
): [number, OnChangeFn<number | null | undefined>] {
  const defaultPageSize = useDefaultPageSize();
  const [raw, setSize] = useQueryParam(queryKey, NumberParam, {
    updateType: "replaceIn",
  });

  const size =
    typeof raw === "number" && Number.isInteger(raw) && raw > 0
      ? raw
      : defaultPageSize;

  return [size, setSize];
}
