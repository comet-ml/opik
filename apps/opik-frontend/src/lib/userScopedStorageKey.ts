import { useMemo } from "react";
import useAppStore from "@/store/AppStore";
import { isDefaultUser } from "@/constants/user";

// Returns a localStorage key namespaced by the current userName so per-user
// state (e.g. onboarding completion) does not leak across accounts sharing
// a browser. The key is derived reactively — on the first render after the
// WorkspacePreloader mounts its children, AppStore.user.userName is still
// the DEFAULT_USERNAME sentinel because setAppUser runs inside a useEffect;
// once that effect fires the userName flips to the real user and this hook
// recomputes the key, prompting use-local-storage-state to re-read from
// the correct namespaced slot.
//
// Migration from a legacy un-namespaced value must run synchronously inside
// the same render that flips the key — if we deferred it to useEffect, the
// consumer's useLocalStorageState would read the empty namespaced slot
// first and only discover the migrated value on the next unrelated render.
// Gating on a resolved (non-default) userName keeps the legacy value intact
// until /auth/test has resolved the real user.
export function useUserScopedStorageKey(baseKey: string): string {
  const userName = useAppStore((state) => state.user.userName);

  return useMemo(() => {
    const scoped = `${baseKey}:${userName}`;
    if (isDefaultUser(userName)) return scoped;

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
  }, [baseKey, userName]);
}
