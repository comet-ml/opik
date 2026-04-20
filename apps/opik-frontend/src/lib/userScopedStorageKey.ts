import { useEffect, useMemo } from "react";
import useAppStore from "@/store/AppStore";
import { isDefaultUser } from "@/constants/user";

// Returns a localStorage key namespaced by the current userName so per-user
// state (e.g. onboarding completion) does not leak across accounts sharing
// a browser. The key is derived reactively so it follows the
// DEFAULT_USERNAME → real user transition that happens after /auth/test
// resolves in WorkspacePreloader.
//
// The previous un-namespaced key is cleaned up once a real user is known —
// we deliberately do NOT migrate its value into the namespaced slot: the
// legacy blob has no user identity attached, so copying it into whichever
// account happens to log in first post-deploy would re-introduce the same
// cross-account leak this hook exists to prevent. Pre-PR users re-onboard
// once, then the namespaced state persists.
export function useUserScopedStorageKey(baseKey: string): string {
  const userName = useAppStore((state) => state.user.userName);

  const key = useMemo(() => `${baseKey}:${userName}`, [baseKey, userName]);

  useEffect(() => {
    if (isDefaultUser(userName)) return;

    try {
      localStorage.removeItem(baseKey);
    } catch {
      // localStorage unavailable (e.g. Safari private mode); namespacing
      // still works in-memory via use-local-storage-state's fallback.
    }
  }, [baseKey, userName]);

  return key;
}
