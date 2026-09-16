import { useCallback, useState } from "react";

// In-memory stand-in for use-local-storage-state that keeps real React state,
// so "the value survives a remount" is a real assertion. Share one `storage`
// object per test file and clear it between tests.
export const createLocalStorageStateMock = (storage: Record<string, unknown>) =>
  function useLocalStorageStateMock(
    key: string,
    options?: { defaultValue?: unknown },
  ) {
    const [value, setValue] = useState(() =>
      key in storage ? storage[key] : options?.defaultValue,
    );

    const set = useCallback(
      (next: unknown) => {
        const resolved = typeof next === "function" ? next(value) : next;
        storage[key] = resolved;
        setValue(resolved);
      },
      [key, value],
    );

    return [value, set];
  };
