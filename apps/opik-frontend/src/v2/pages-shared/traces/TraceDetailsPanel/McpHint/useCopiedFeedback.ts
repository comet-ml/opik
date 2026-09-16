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
  const [hasCopied, setHasCopied] = useState(false);

  useEffect(() => {
    if (!hasCopied) return;
    const timer = setTimeout(() => setHasCopied(false), MCP_COPIED_FEEDBACK_MS);
    return () => clearTimeout(timer);
  }, [hasCopied]);

  const copyText = useCallback(async (text: string) => {
    try {
      await copy(text);
    } catch {
      return false;
    }
    setHasCopied(true);
    return true;
  }, []);

  return [hasCopied, copyText];
};

export default useCopiedFeedback;
