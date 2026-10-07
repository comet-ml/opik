export type SessionStorageMemory<T> = {
  load: () => T | undefined;
  save: (value: T | undefined) => void;
};

const isEmpty = (value: unknown) =>
  value === undefined ||
  value === "" ||
  (Array.isArray(value) && value.length === 0);

// Per-tab memory: sessionStorage can be blocked or full, so failures are swallowed.
export const createSessionStorageMemory = <T>(
  key: string,
): SessionStorageMemory<T> => ({
  load: () => {
    try {
      const raw = window.sessionStorage.getItem(key);
      return raw === null ? undefined : (JSON.parse(raw) as T);
    } catch {
      return undefined;
    }
  },
  save: (value) => {
    try {
      if (isEmpty(value)) {
        window.sessionStorage.removeItem(key);
      } else {
        window.sessionStorage.setItem(key, JSON.stringify(value));
      }
    } catch {
      // Remembering is best-effort.
    }
  },
});
