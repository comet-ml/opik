import { useCallback } from "react";
import { NumberParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { useDefaultPageSize } from "@/contexts/feature-toggles-provider";
import { OnChangeFn } from "@/types/shared";

const DEFAULT_QUERY_KEY = "size";

const isValidPageSize = (value: unknown): value is number =>
  typeof value === "number" && Number.isInteger(value) && value > 0;

// Shared page-size wiring for tables that read pagination size from the URL.
// Precedence:
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

  const size = isValidPageSize(raw) ? raw : defaultPageSize;

  return [size, setSize];
}

// Variant for tables that want cross-session persistence of the user's
// page-size choice (v1 Experiments tables historically did this). Layers
// localStorage between URL and deployment default. Precedence:
//   1. Valid ?size= in the URL.
//   2. Valid value in localStorage (only set when the user actively picks one).
//   3. Deployment default from useDefaultPageSize().
//
// We deliberately pass NO defaultValue to useLocalStorageState - storage stays
// undefined until the user picks a size. This avoids the race where the FE
// fallback (100) would otherwise be seeded into storage on first render and
// shadow the real deployment default once the toggles fetch resolves.
export function useTablePageSizeWithStorage(
  localStorageKey: string,
  queryKey: string = DEFAULT_QUERY_KEY,
): [number, OnChangeFn<number | null | undefined>] {
  const defaultPageSize = useDefaultPageSize();
  const [urlSize, setUrlSize] = useQueryParam(queryKey, NumberParam, {
    updateType: "replaceIn",
  });
  const [storedSize, setStoredSize] = useLocalStorageState<number | undefined>(
    localStorageKey,
  );

  const size = isValidPageSize(urlSize)
    ? urlSize
    : isValidPageSize(storedSize)
      ? storedSize
      : defaultPageSize;

  const setSize = useCallback<OnChangeFn<number | null | undefined>>(
    (value) => {
      setUrlSize(value as number | null | undefined);
      if (isValidPageSize(value)) {
        setStoredSize(value);
      }
    },
    [setUrlSize, setStoredSize],
  );

  return [size, setSize];
}
