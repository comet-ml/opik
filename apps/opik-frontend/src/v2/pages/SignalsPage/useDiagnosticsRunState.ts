import { useCallback, useEffect, useState } from "react";

const STORAGE_KEY = (projectId: string) => `diagnostics-run:${projectId}`;
const RUN_CHANGE_EVENT = "diagnostics-run-change";
// Fallback for a run the backend never reports on.
const RUN_TIMEOUT_MS = 5 * 60 * 1000;

type StoredState =
  | {
      status: "running";
      startedAt: number;
      baseline: number;
      failBaseline: number;
    }
  | { status: "failed"; reason?: string; detail?: string };

const readState = (projectId: string): StoredState | null => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredState;
    if (parsed?.status === "running" && typeof parsed.startedAt === "number") {
      return parsed;
    }
    if (parsed?.status === "failed") return parsed;
    return null;
  } catch {
    return null;
  }
};

const useDiagnosticsRunState = (projectId: string) => {
  const [state, setState] = useState<StoredState | null>(() =>
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
    (next: StoredState | null) => {
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
      write({
        status: "running",
        startedAt: Date.now(),
        baseline,
        failBaseline,
      });
    },
    [write],
  );

  const endRun = useCallback(() => write(null), [write]);

  const failRun = useCallback(
    (reason?: string, detail?: string) => {
      write({ status: "failed", reason, detail });
    },
    [write],
  );

  const dismissFailure = useCallback(() => write(null), [write]);

  const isRunning = state?.status === "running";

  useEffect(() => {
    if (!isRunning || state?.status !== "running") return;
    const remaining = state.startedAt + RUN_TIMEOUT_MS - Date.now();
    if (remaining <= 0) {
      endRun();
      return;
    }
    const timer = setTimeout(endRun, remaining);
    return () => clearTimeout(timer);
  }, [isRunning, state, endRun]);

  return {
    isRunning,
    baseline: state?.status === "running" ? state.baseline : 0,
    failBaseline: state?.status === "running" ? state.failBaseline : 0,
    failedReason: state?.status === "failed" ? state.reason : undefined,
    startRun,
    endRun,
    failRun,
    dismissFailure,
  };
};

export default useDiagnosticsRunState;
