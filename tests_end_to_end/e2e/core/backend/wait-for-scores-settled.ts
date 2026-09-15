import type { TraceDetail } from './client';

export interface WaitForScoresSettledOpts {
  /** How long the score set must stay unchanged before it counts as settled. */
  quietPeriodMs?: number;
  timeoutMs?: number;
  pollIntervalMs?: number;
  /**
   * Minimum number of feedback scores required before the quiet period is
   * allowed to start. Defaults to 1: an empty score set is treated as "the
   * evaluators have not produced anything yet", not as settled, so a slow
   * evaluator cannot be mistaken for a rule that correctly declined to score.
   * Set to 0 only when a genuinely empty result is the expected end state.
   */
  minScores?: number;
}

/**
 * Poll a trace until its feedback-score set stops changing for `quietPeriodMs`,
 * then return the settled trace.
 *
 * Needed because a single rule's score landing does NOT mean every rule has
 * finished with the trace: OnlineScoringSampler enqueues rules via
 * `evaluators.parallelStream()` onto per-type Redis streams that are consumed
 * asynchronously, so there is no ordering guarantee between two rules' scores.
 * Asserting "rule X did not score this trace" immediately after rule Y's score
 * arrives can therefore pass while X is still in flight.
 *
 * Throws if the trace is missing — a deleted or never-ingested trace must fail
 * loudly rather than present as an empty score list, which would make an
 * absence assertion pass vacuously.
 */
export async function waitForTraceScoresSettled(
  getTrace: (traceId: string) => Promise<TraceDetail | null>,
  traceId: string,
  opts: WaitForScoresSettledOpts = {},
): Promise<TraceDetail> {
  const quietPeriodMs = opts.quietPeriodMs ?? 10_000;
  const timeoutMs = opts.timeoutMs ?? 90_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 2_000;
  const minScores = opts.minScores ?? 1;

  const start = Date.now();
  let lastFingerprint: string | null = null;
  let lastChangeAt = Date.now();
  let lastTrace: TraceDetail | null = null;

  while (Date.now() - start < timeoutMs) {
    lastTrace = await getTrace(traceId);
    if (lastTrace === null) {
      throw new Error(
        `waitForTraceScoresSettled: trace ${traceId} not found — ` +
          `cannot assert on its feedback scores.`,
      );
    }

    const fingerprint = lastTrace.feedbackScores
      .map((fs) => `${fs.name}=${fs.value}`)
      .sort()
      .join(',');

    if (fingerprint !== lastFingerprint) {
      lastFingerprint = fingerprint;
      lastChangeAt = Date.now();
    } else if (
      lastTrace.feedbackScores.length >= minScores &&
      Date.now() - lastChangeAt >= quietPeriodMs
    ) {
      return lastTrace;
    }

    // Never sleep past the deadline — otherwise the effective timeout overruns
    // by up to one poll interval, which matters when a caller has sized this
    // wait to fit inside a larger test budget.
    const remaining = timeoutMs - (Date.now() - start);
    if (remaining <= 0) break;
    await new Promise((r) => setTimeout(r, Math.min(pollIntervalMs, remaining)));
  }

  const observedCount = lastTrace?.feedbackScores.length ?? 0;
  const reason =
    observedCount < minScores
      ? `only ${observedCount} feedback score(s) ever appeared (need at least ${minScores})`
      : `feedback scores never stayed unchanged for ${quietPeriodMs}ms`;
  throw new Error(
    `waitForTraceScoresSettled timed out after ${Date.now() - start}ms on trace ${traceId}: ` +
      `${reason}. Last observed: [${lastFingerprint ?? '<none>'}]`,
  );
}
