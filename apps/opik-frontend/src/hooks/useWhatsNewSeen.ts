import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = "whats-new-seen-version";
const SEEN_CHANGE_EVENT = "whats-new-seen-change";

const readSeen = (): string | null => {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
};

const writeSeen = (version: string) => {
  try {
    localStorage.setItem(STORAGE_KEY, version);
  } catch {
    // A browser with storage disabled just loses the indicator, which must not break the panel.
  }
  window.dispatchEvent(new Event(SEEN_CHANGE_EVENT));
};

/**
 * Tracks the last deployed version this user has seen the What's new panel for.
 *
 * Keyed on the version rather than a timestamp so the indicator is driven by the thing that
 * actually changed — an upgrade re-raises it for everyone, and re-reading the same version does not.
 */
const useWhatsNewSeen = (currentVersion?: string | null) => {
  const [lastSeenVersion, setLastSeenVersion] = useState<string | null>(
    readSeen,
  );

  useEffect(() => {
    const sync = () => setLastSeenVersion(readSeen());
    window.addEventListener(SEEN_CHANGE_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(SEEN_CHANGE_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, []);

  // Establish the baseline on first sight of a deployment, silently. Without this the indicator
  // could never fire: it marks a *change* of version, so with nothing stored there is nothing to
  // change from, and a user who has never opened the panel would never be told to.
  useEffect(() => {
    if (!currentVersion || readSeen() !== null) return;
    writeSeen(currentVersion);
    setLastSeenVersion(currentVersion);
  }, [currentVersion]);

  const markSeen = useCallback(() => {
    if (!currentVersion || readSeen() === currentVersion) return;
    writeSeen(currentVersion);
    setLastSeenVersion(currentVersion);
  }, [currentVersion]);

  return {
    hasUnseenRelease: Boolean(
      currentVersion && lastSeenVersion && lastSeenVersion !== currentVersion,
    ),
    markSeen,
  };
};

export default useWhatsNewSeen;
