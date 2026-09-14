import { useEffect, useState } from "react";

type UseRevealOnExpandArgs = {
  /** Whether the user currently has the thing open. */
  active: boolean;
  /** What the reveal is about. A new subject has not been asked about yet. */
  subject: string;
};

/**
 * Shows something for exactly as long as the user has the thing open, and only
 * on the subject they opened.
 *
 * Two rules:
 *
 * - open shows it, closed hides it — no delay, no lingering. The fade carries
 *   the "this is a response to what you just did" reading that a timed reveal
 *   used to;
 * - an opening counts only for the subject it happened on. The shared
 *   collapsible keeps its open state when the user moves to another span, so
 *   without this the hint would appear on a failure nobody opened — and would
 *   report an impression with no expansion in front of it.
 */
const useRevealOnExpand = ({
  active,
  subject,
}: UseRevealOnExpandArgs): boolean => {
  // The subject the current opening was made on, or null while closed.
  const [openedFor, setOpenedFor] = useState<string | null>(null);

  useEffect(() => {
    if (!active) {
      setOpenedFor(null);
      return;
    }
    // Keeps the subject the opening was made on, rather than adopting whatever
    // is in front of the user now.
    setOpenedFor((current) => current ?? subject);
  }, [active, subject]);

  return openedFor === subject;
};

export default useRevealOnExpand;
