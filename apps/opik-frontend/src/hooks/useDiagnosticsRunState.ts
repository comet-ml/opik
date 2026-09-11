import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = (projectId: string) => `diagnostics-run:${projectId}`;
const RUN_CHANGE_EVENT = "diagnostics-run-change";
// Fallback for a run the backend never reports on.
const RUN_TIMEOUT_MS = 5 * 60 * 1000;

// Only the client-side "running" concept is persisted here; the failed state is
// derived from the job (the backend owns it and clears it on the next success).
// `baseline` / `failBaseline` are the issue-update and last_failed_at timestamps
// captured at trigger, used to recognize when *this* run resolves.
type RunState = {
  startedAt: number;
  baseline: number;
  failBaseline: number;
};

const readState = (projectId: string): RunState | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<RunState>;
    if (typeof parsed?.startedAt !== "number") return null;
    // Tolerate the pre-failBaseline shape ({ startedAt, baseline }) so a run in
    // flight across a deploy isn't dropped on reload. Backfill the missing
    // failBaseline to the run's own startedAt — not 0 — so only a failure recorded
    // *after* the run began counts; a stale prior last_failed_at is ignored.
    return {
      startedAt: parsed.startedAt,
      baseline: typeof parsed.baseline === "number" ? parsed.baseline : 0,
      failBaseline:
        typeof parsed.failBaseline === "number"
          ? parsed.failBaseline
          : parsed.startedAt,
    };
  } catch {
    return null;
  }
};

const useDiagnosticsRunState = (projectId: string) => {
  const [state, setState] = useState<RunState | null>(() =>
    readState(projectId),
  );

  useEffect(() => {
    setState(readState(projectId));
    const sync = () => setState(readState(projectId));
    window.addEventListener(RUN_CHANGE_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(RUN_CHANGE_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, [projectId]);

  const write = useCallback(
    (next: RunState | null) => {
      if (next) {
        localStorage.setItem(STORAGE_KEY(projectId), JSON.stringify(next));
      } else {
        localStorage.removeItem(STORAGE_KEY(projectId));
      }
      setState(next);
      window.dispatchEvent(new Event(RUN_CHANGE_EVENT));
    },
    [projectId],
  );

  const startRun = useCallback(
    (baseline: number, failBaseline: number) => {
      write({ startedAt: Date.now(), baseline, failBaseline });
    },
    [write],
  );

  const endRun = useCallback(() => write(null), [write]);

  const isRunning = state !== null;

  useEffect(() => {
    if (!state) return;
    const remaining = state.startedAt + RUN_TIMEOUT_MS - Date.now();
    if (remaining <= 0) {
      endRun();
      return;
    }
    const timer = setTimeout(endRun, remaining);
    return () => clearTimeout(timer);
  }, [state, endRun]);

  return {
    isRunning,
    startedAt: state?.startedAt ?? 0,
    baseline: state?.baseline ?? 0,
    failBaseline: state?.failBaseline ?? 0,
    startRun,
    endRun,
  };
};

export default useDiagnosticsRunState;
