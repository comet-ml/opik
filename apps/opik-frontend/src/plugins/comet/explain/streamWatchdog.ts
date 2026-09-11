/**
 * Per-stream watchdog for the explain store.
 *
 * A request that never reaches a live pod produces no chunk at all. The watchdog
 * arms two timers per stream so the popover can't hang on "Thinking…": a `waking`
 * nudge first, then a hard `timeout`. The callbacks decide what to do — they
 * re-read current store state, since a chunk may have arrived (and disarmed the
 * timers) in the meantime.
 *
 * Timers are side-effect handles keyed by explainId, deliberately kept out of the
 * store's render state.
 */
export interface StreamWatchdog {
  /** Start the waking + timeout timers for a stream (re-arming clears prior ones). */
  arm: (explainId: string) => void;
  /** Clear both timers for a stream. Idempotent. */
  disarm: (explainId: string) => void;
}

interface StreamWatchdogOptions {
  wakingMs: number;
  timeoutMs: number;
  onWaking: (explainId: string) => void;
  onTimeout: (explainId: string) => void;
}

type Handles = {
  waking: ReturnType<typeof setTimeout>;
  timeout: ReturnType<typeof setTimeout>;
};

export const createStreamWatchdog = ({
  wakingMs,
  timeoutMs,
  onWaking,
  onTimeout,
}: StreamWatchdogOptions): StreamWatchdog => {
  const timers = new Map<string, Handles>();

  const disarm: StreamWatchdog["disarm"] = (explainId) => {
    const handles = timers.get(explainId);
    if (!handles) return;
    clearTimeout(handles.waking);
    clearTimeout(handles.timeout);
    timers.delete(explainId);
  };

  return {
    disarm,
    arm: (explainId) => {
      disarm(explainId);
      timers.set(explainId, {
        waking: setTimeout(() => onWaking(explainId), wakingMs),
        timeout: setTimeout(() => onTimeout(explainId), timeoutMs),
      });
    },
  };
};
