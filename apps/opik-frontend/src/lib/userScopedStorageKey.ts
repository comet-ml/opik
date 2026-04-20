import { useState } from "react";
import useAppStore from "@/store/AppStore";

// Returns a localStorage key namespaced by the current userName so that
// per-user state (e.g. onboarding completion) does not leak across accounts
// that share a browser. On first mount, if a legacy un-namespaced value is
// present and the namespaced slot is empty, the legacy value is migrated
// in place — this preserves completion state for users already onboarded
// before this change shipped.
export function useUserScopedStorageKey(baseKey: string): string {
  const userName = useAppStore((state) => state.user.userName);

  const [key] = useState(() => {
    const scoped = `${baseKey}:${userName}`;
    try {
      const legacy = localStorage.getItem(baseKey);
      if (legacy !== null && localStorage.getItem(scoped) === null) {
        localStorage.setItem(scoped, legacy);
        localStorage.removeItem(baseKey);
      }
    } catch {
      // localStorage unavailable (e.g. Safari private mode); namespacing
      // still works in-memory via use-local-storage-state's fallback.
    }
    return scoped;
  });

  return key;
}
