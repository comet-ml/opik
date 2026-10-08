export type LocalStorageMemory<T> = {
  load: () => T | undefined;
  save: (value: T | undefined) => void;
};

const isEmpty = (value: unknown) =>
  value === undefined ||
  value === "" ||
  (Array.isArray(value) && value.length === 0);

// Best-effort memory: localStorage can be blocked or full, so failures are swallowed.
export const createLocalStorageMemory = <T>(
  key: string,
): LocalStorageMemory<T> => ({
  load: () => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? undefined : (JSON.parse(raw) as T);
    } catch {
      return undefined;
    }
  },
  save: (value) => {
    try {
      if (isEmpty(value)) {
        window.localStorage.removeItem(key);
      } else {
        window.localStorage.setItem(key, JSON.stringify(value));
      }
    } catch {
      // Remembering is best-effort.
    }
  },
});
