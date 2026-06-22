import { useCallback, useEffect, useState } from "react";

// The backend trigger is fire-and-forget with no completion signal, so the
// "running" state is tracked client-side: we record when a run was started
// (and a baseline of the newest issue's update time) and clear it once results
// land or a timeout elapses. Persisted per project so it survives navigation
// and reloads while a run is still in flight.
const STORAGE_KEY = (projectId: string) => `diagnostics-run:${projectId}`;
// Fallback only — the run normally clears as soon as results land (see below).
// Kept comfortably above a typical run so the "Run diagnostic" button can't
// re-enable mid-run and let a second, concurrent run start (which would create
// duplicate issues instead of refreshing the existing ones).
const RUN_TIMEOUT_MS = 12 * 60 * 1000;

type RunState = {
  // Client epoch ms at trigger — used only for the timeout fallback.
  startedAt: number;
  // Newest issue `last_updated_at` (server epoch ms) at trigger time. Completion
  // is detected when the newest update advances past this, comparing server
  // times to server times so client/server clock skew can't trip it.
  baseline: number;
};

const readRunState = (projectId: string): RunState | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as RunState;
    return typeof parsed?.startedAt === "number" ? parsed : null;
  } catch {
    return null;
  }
};

const useDiagnosticsRunState = (projectId: string) => {
  const [runState, setRunState] = useState<RunState | null>(() =>
    readRunState(projectId),
  );

  useEffect(() => {
    setRunState(readRunState(projectId));
  }, [projectId]);

  const startRun = useCallback(
    (baseline: number) => {
      const next: RunState = { startedAt: Date.now(), baseline };
      localStorage.setItem(STORAGE_KEY(projectId), JSON.stringify(next));
      setRunState(next);
    },
    [projectId],
  );

  const endRun = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY(projectId));
    setRunState(null);
  }, [projectId]);

  // Stop showing "running" after the timeout even if no results landed — a run
  // that confirms no issues leaves every `last_updated_at` untouched.
  useEffect(() => {
    if (!runState) return;
    const remaining = runState.startedAt + RUN_TIMEOUT_MS - Date.now();
    if (remaining <= 0) {
      endRun();
      return;
    }
    const timer = setTimeout(endRun, remaining);
    return () => clearTimeout(timer);
  }, [runState, endRun]);

  return {
    isRunning: runState !== null,
    baseline: runState?.baseline ?? 0,
    startRun,
    endRun,
  };
};

export default useDiagnosticsRunState;
