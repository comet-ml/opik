import { useCallback } from "react";
import { NumberParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { useDefaultPageSize } from "@/contexts/feature-toggles-provider";
import { OnChangeFn } from "@/types/shared";

const isValidPageSize = (value: unknown): value is number =>
  typeof value === "number" && Number.isInteger(value) && value > 0;

// Page-size wiring for tables that read pagination size from the URL with
// cross-session persistence of the user's choice. Precedence:
//   1. Valid ?size= in the URL (shareable links).
//   2. Valid value previously written to localStorage.
//   3. Deployment default from ServiceTogglesConfig.defaultPageSize, exposed
//      by FeatureTogglesProvider as useDefaultPageSize().
//
// We deliberately pass NO defaultValue to useLocalStorageState - storage stays
// empty until the user actively picks a size. Otherwise the FE fallback (100)
// would be seeded into storage on first render and shadow the real deployment
// default once the toggles fetch resolves.
//
// Known limitation: if the user picks a size from the dropdown *before* the
// toggles request resolves, setSize writes the FE fallback (100) - not the
// deployment default - to localStorage. Acceptable: the fallback only ever
// loses to a real user-picked value, and the toggles fetch is sub-100ms in
// practice.
export default function useTablePageSize(
  localStorageKey: string,
): [number, OnChangeFn<number | null | undefined>] {
  const defaultPageSize = useDefaultPageSize();
  const [urlSize, setUrlSize] = useQueryParam("size", NumberParam, {
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
