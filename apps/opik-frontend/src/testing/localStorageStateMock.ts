import { useCallback, useState } from "react";

/**
 * A working in-memory stand-in for `use-local-storage-state`.
 *
 * It holds real state, so "the value survives a remount" is an assertion about
 * the code under test rather than a mock echoing itself back. Pass the same
 * store object to every mock in a file and clear it between tests.
 *
 *   const storage: Record<string, unknown> = {};
 *   vi.mock("use-local-storage-state", async () => ({
 *     default: (await import("@/testing/localStorageStateMock"))
 *       .createLocalStorageStateMock(storage),
 *   }));
 */
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
