import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = (projectId: string) => `diagnostics-seen:${projectId}`;
const SEEN_CHANGE_EVENT = "diagnostics-seen-change";

const readSeen = (projectId: string): string | null => {
  try {
    return localStorage.getItem(STORAGE_KEY(projectId));
  } catch {
    return null;
  }
};

const useDiagnosticsSeen = (projectId: string) => {
  const [lastSeen, setLastSeen] = useState<string | null>(() =>
    readSeen(projectId),
  );

  useEffect(() => {
    setLastSeen(readSeen(projectId));
    const sync = () => setLastSeen(readSeen(projectId));
    window.addEventListener(SEEN_CHANGE_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(SEEN_CHANGE_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, [projectId]);

  const markSeen = useCallback(
    (scanAt: string) => {
      if (!projectId || !scanAt) return;
      const current = readSeen(projectId);
      if (current && Date.parse(current) >= Date.parse(scanAt)) return;
      localStorage.setItem(STORAGE_KEY(projectId), scanAt);
      setLastSeen(scanAt);
      window.dispatchEvent(new Event(SEEN_CHANGE_EVENT));
    },
    [projectId],
  );

  return { lastSeen, markSeen };
};

export default useDiagnosticsSeen;
