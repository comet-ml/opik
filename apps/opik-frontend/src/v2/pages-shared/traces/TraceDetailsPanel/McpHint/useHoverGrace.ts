import { useCallback, useEffect, useRef, useState } from "react";

import { MCP_HINT_HOVER_GRACE_MS } from "./constants";

type UseHoverGraceResult = {
  isOpen: boolean;
  /** Open now, and drop any close the pointer left behind on its way in. */
  open: () => void;
  /** Close now — for dismissals that are not the pointer wandering off. */
  closeNow: () => void;
  /** The pointer left; close unless it comes back within the grace period. */
  closeAfterGrace: () => void;
};

/**
 * Open-on-hover that survives the trip between two elements.
 *
 * The button and the popover are separate elements with a gap between them, so
 * crossing it fires a leave on one and an enter on the other back to back. A
 * naive "leave closes it" would dismiss the popover mid-movement, and a cursor
 * that clips a corner would do the same. Leaving therefore only *schedules* a
 * close; anything that re-enters cancels it, and the newest intent always wins.
 */
const useHoverGrace = (
  graceMs: number = MCP_HINT_HOVER_GRACE_MS,
): UseHoverGraceResult => {
  const [isOpen, setIsOpen] = useState(false);
  const closeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const cancelPendingClose = useCallback(() => {
    if (closeTimeoutRef.current === null) return;
    clearTimeout(closeTimeoutRef.current);
    closeTimeoutRef.current = null;
  }, []);

  const open = useCallback(() => {
    cancelPendingClose();
    setIsOpen(true);
  }, [cancelPendingClose]);

  const closeNow = useCallback(() => {
    cancelPendingClose();
    setIsOpen(false);
  }, [cancelPendingClose]);

  const closeAfterGrace = useCallback(() => {
    cancelPendingClose();
    closeTimeoutRef.current = setTimeout(() => {
      closeTimeoutRef.current = null;
      setIsOpen(false);
    }, graceMs);
  }, [cancelPendingClose, graceMs]);

  useEffect(() => cancelPendingClose, [cancelPendingClose]);

  return { isOpen, open, closeNow, closeAfterGrace };
};

export default useHoverGrace;
