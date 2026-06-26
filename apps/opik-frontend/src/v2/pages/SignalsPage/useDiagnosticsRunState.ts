import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = (projectId: string) => `diagnostics-run:${projectId}`;
const RUN_CHANGE_EVENT = "diagnostics-run-change";
const RUN_TIMEOUT_MS = 12 * 60 * 1000;

type RunState = {
  startedAt: number;
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
    const sync = () => setRunState(readRunState(projectId));
    window.addEventListener(RUN_CHANGE_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(RUN_CHANGE_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, [projectId]);

  const startRun = useCallback(
    (baseline: number) => {
      const next: RunState = { startedAt: Date.now(), baseline };
      localStorage.setItem(STORAGE_KEY(projectId), JSON.stringify(next));
      setRunState(next);
      window.dispatchEvent(new Event(RUN_CHANGE_EVENT));
    },
    [projectId],
  );

  const endRun = useCallback(() => {
    localStorage.removeItem(STORAGE_KEY(projectId));
    setRunState(null);
    window.dispatchEvent(new Event(RUN_CHANGE_EVENT));
  }, [projectId]);

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
