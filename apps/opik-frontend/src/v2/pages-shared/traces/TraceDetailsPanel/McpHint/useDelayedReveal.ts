import { useEffect, useRef, useState } from "react";

import { MCP_HINT_REVEAL_DELAY_MS } from "./constants";

type UseDelayedRevealArgs = {
  /** Whether the user is currently expressing the intent the reveal responds to. */
  active: boolean;
  /** What the reveal is about. A new subject has not earned the reveal yet. */
  subject: string;
  delay?: number;
};

/**
 * Reveals something a beat after the user asks for it, and — deliberately —
 * never takes it back.
 *
 * Four rules, and the second is the one that surprises people:
 *
 * - going active starts the delay; the reveal lands when it elapses;
 * - going inactive **cancels a pending** reveal but **does not retract** one
 *   that already happened, because the user asking and then tidying up is not
 *   the user changing their mind;
 * - a new `subject` retracts unconditionally — the reveal was earned for the
 *   thing in front of the user, and that thing has changed;
 * - and an activation counts only for the subject it happened on. Something
 *   left active across a change of subject is not a fresh ask, so it earns
 *   nothing: the user has to ask again.
 */
const useDelayedReveal = ({
  active,
  subject,
  delay = MCP_HINT_REVEAL_DELAY_MS,
}: UseDelayedRevealArgs): boolean => {
  const [isRevealed, setIsRevealed] = useState(false);
  // Which subject the current activation was made for, or null while inactive.
  const activatedForRef = useRef<string | null>(null);

  useEffect(() => {
    setIsRevealed(false);
  }, [subject]);

  useEffect(() => {
    if (!active) {
      activatedForRef.current = null;
      return;
    }
    activatedForRef.current ??= subject;
    if (activatedForRef.current !== subject) return;

    const timeout = setTimeout(() => setIsRevealed(true), delay);

    // Only ever clears a *pending* reveal: `isRevealed` is left alone, which is
    // what keeps an already-shown control on screen when the user goes inactive.
    return () => clearTimeout(timeout);
  }, [active, subject, delay]);

  return isRevealed;
};

export default useDelayedReveal;
