import type { FeedbackScoreRef, TraceDetail } from './client';

export interface PollFeedbackScoreOpts {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/**
 * Poll a trace's REST view until a feedback_score with the given name appears,
 * or throw with a verbose diagnostic message after the timeout. Used to assert
 * on asynchronously-produced state landed by the online evaluation engine.
 */
export async function pollTraceForFeedbackScore(
  getTrace: (traceId: string) => Promise<TraceDetail | null>,
  traceId: string,
  scoreName: string,
  opts: PollFeedbackScoreOpts = {},
): Promise<FeedbackScoreRef> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 2_000;
  const start = Date.now();
  let lastTrace: TraceDetail | null = null;

  while (Date.now() - start < timeoutMs) {
    lastTrace = await getTrace(traceId);
    const hit = lastTrace?.feedbackScores.find((fs) => fs.name === scoreName);
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }

  const elapsed = Date.now() - start;
  const lastScores =
    lastTrace === null
      ? '<trace not found>'
      : `[${lastTrace.feedbackScores.map((fs) => `${fs.name}=${fs.value}`).join(', ')}]`;
  throw new Error(
    `pollTraceForFeedbackScore timed out after ${elapsed}ms ` +
      `waiting for feedback_score "${scoreName}" on trace ${traceId}. ` +
      `Last polled feedback_scores: ${lastScores}`,
  );
}
