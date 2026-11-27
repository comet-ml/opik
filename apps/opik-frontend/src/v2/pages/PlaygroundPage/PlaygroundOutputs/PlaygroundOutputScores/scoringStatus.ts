import { useEffect, useReducer } from "react";

import {
  ONLINE_SCORING_REASON,
  ONLINE_SCORING_STATE,
  OnlineScoringStatus,
} from "@/types/automations";

export const REFETCH_INTERVAL = 5000;
// The stream retries a failed scoring about every 10 minutes, so there is no point looking sooner.
const RETRYING_REFETCH_INTERVAL = 30000;
const MAX_REFETCH_TIME = 300000;
// After the wait, a score still owed is looked for once a minute for a while: a judge whose provider hangs only
// reports once its calls time out, well after the wait, and can take close to half an hour to report on a suite.
const LATE_REFETCH_INTERVAL = 60000;
const LATE_REFETCH_TIME = 2700000;
// A retry reports again within about 14 minutes (the 10-minute reclaim plus a hanging attempt). A retrying status
// older than this was never followed by its outcome, so it is no reason to keep looking.
const RETRYING_STALE_AFTER = 1200000;

const REASON_TEXT: Record<ONLINE_SCORING_REASON, string> = {
  [ONLINE_SCORING_REASON.NOT_CONFIGURED]:
    "No API key is configured for the judge model's provider",
  [ONLINE_SCORING_REASON.AUTHENTICATION]: "The provider rejected the API key",
  [ONLINE_SCORING_REASON.NOT_FOUND]: "The provider doesn't serve this model",
  [ONLINE_SCORING_REASON.REJECTED]: "The provider rejected the request",
  [ONLINE_SCORING_REASON.RATE_LIMITED]: "The provider is rate limiting calls",
  [ONLINE_SCORING_REASON.TIMEOUT]: "The scoring call timed out",
  [ONLINE_SCORING_REASON.UNREACHABLE]: "The provider can't be reached",
  [ONLINE_SCORING_REASON.SERVER_ERROR]:
    "The scoring call failed with a server error",
  [ONLINE_SCORING_REASON.NO_SCORE]:
    "The evaluation was skipped or produced no usable score",
  [ONLINE_SCORING_REASON.UNRESOLVED_ARGUMENTS]:
    "The metric's arguments couldn't be filled from the trace",
  [ONLINE_SCORING_REASON.OTHER]: "Scoring failed unexpectedly",
};

// When to stop waiting for scores: five minutes after the run ended, so a reload doesn't restart the wait.
// Until the run's end is known, opening the page is the best guess.
export const waitEndOf = (endedAt: string | undefined, openedAt: number) =>
  (endedAt ? Date.parse(endedAt) : openedAt) + MAX_REFETCH_TIME;

export const lateWaitEndOf = (endedAt: string | undefined, openedAt: number) =>
  (endedAt ? Date.parse(endedAt) : openedAt) + LATE_REFETCH_TIME;

// Read at render, so a wait end that moves later counts at once. Polling stops when the wait ends, so the timer
// re-renders to show it is over.
export const useWaitOver = (waitEnd: number) => {
  const [, rerender] = useReducer((count: number) => count + 1, 0);

  useEffect(() => {
    const remaining = waitEnd - Date.now();
    if (remaining <= 0) return;
    const timer = setTimeout(rerender, remaining);
    return () => clearTimeout(timer);
  }, [waitEnd]);

  return waitEnd <= Date.now();
};

export const isRetrying = (status: OnlineScoringStatus | undefined) =>
  status?.state === ONLINE_SCORING_STATE.RETRYING;

export const isFinalStatus = (status: OnlineScoringStatus | undefined) =>
  status?.state === ONLINE_SCORING_STATE.FAILED ||
  status?.state === ONLINE_SCORING_STATE.SKIPPED;

export const isRetryingRecently = (status: OnlineScoringStatus | undefined) =>
  !!status &&
  isRetrying(status) &&
  Date.parse(status.updated_at) + RETRYING_STALE_AFTER > Date.now();

/**
 * How often to look for scores and statuses: often until the wait ends, then only while something is still to
 * come. A retry that keeps reporting is followed however long it takes; a score still owed, until the late wait.
 */
export const scoringPollInterval = ({
  waitEnd,
  lateWaitEnd,
  retrying,
  owed,
}: {
  waitEnd: number;
  lateWaitEnd: number;
  retrying: boolean;
  owed: boolean;
}) => {
  const now = Date.now();
  if (waitEnd > now) return REFETCH_INTERVAL;
  if (retrying) return RETRYING_REFETCH_INTERVAL;
  return owed && lateWaitEnd > now ? LATE_REFETCH_INTERVAL : false;
};

export const scoringStatusMessage = (status: OnlineScoringStatus) => {
  const reason = REASON_TEXT[status.reason] ?? REASON_TEXT.other;
  if (status.state === ONLINE_SCORING_STATE.RETRYING) {
    const attempt =
      status.attempt && status.max_attempts
        ? ` (attempt ${status.attempt} of ${status.max_attempts})`
        : "";
    return `${reason}. Retrying${attempt}, which can take several minutes.`;
  }
  return `${reason}. No score will be stored.`;
};
