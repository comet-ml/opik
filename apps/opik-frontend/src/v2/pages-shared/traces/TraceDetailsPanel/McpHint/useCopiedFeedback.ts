import { useCallback, useEffect, useState } from "react";

import copy from "clipboard-copy";
import { MCP_COPIED_FEEDBACK_MS } from "./constants";

/**
 * Copies, then says so for a moment. Shared by the three places that offer a
 * copy, so the wait and the reset cannot drift apart between them.
 *
 * The write is awaited: `clipboard-copy` rejects when the browser refuses, and
 * a tick over a clipboard that never changed is worse than no tick at all.
 */
const useCopiedFeedback = (): [boolean, (text: string) => Promise<boolean>] => {
  // How many copies have landed, rather than a flag: a second copy has to
  // restart the wait, and setting a flag that is already set changes nothing,
  // so the feedback used to end on the first copy's clock. Counting keeps that
  // independent of any clock, which a test can move under us.
  const [copies, setCopies] = useState(0);

  useEffect(() => {
    if (!copies) return;
    const timer = setTimeout(() => setCopies(0), MCP_COPIED_FEEDBACK_MS);
    return () => clearTimeout(timer);
  }, [copies]);

  const copyText = useCallback(async (text: string) => {
    try {
      await copy(text);
    } catch {
      return false;
    }
    setCopies((landed) => landed + 1);
    return true;
  }, []);

  return [copies > 0, copyText];
};

export default useCopiedFeedback;
