import { useRef, useEffect, useCallback } from "react";
import { OnChangeFn } from "@tanstack/react-table";

interface UseScrollRestorationProps {
  data: object;
  scrollPosition?: number;
  onScrollPositionChange?: OnChangeFn<number>;
}

/**
 * Custom hook to manage scroll position restoration across data changes.
 *
 * This hook handles the complex logic of:
 * 1. Restoring scroll position when data or scrollPosition changes
 * 2. Preventing initial scroll events from overwriting saved positions
 * 3. Avoiding feedback loops between scroll events and restorations
 *
 * @param data - The data being displayed (used to detect item changes)
 * @param scrollPosition - The scroll position to restore
 * @param onScrollPositionChange - Callback to save scroll position changes
 * @returns Object containing scrollRef and handleScroll callback
 */
export const useScrollRestoration = ({
  data,
  scrollPosition,
  onScrollPositionChange,
}: UseScrollRestorationProps) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const isRestoringRef = useRef(false);
  const hasRestoredRef = useRef(false);

  // Reset hasRestored flag only when data changes (new item)
  useEffect(() => {
    hasRestoredRef.current = false;
  }, [data]);

  // Restore scroll position when scrollPosition changes
  // Use requestAnimationFrame to ensure restoration happens after content updates
  useEffect(() => {
    if (scrollRef.current && scrollPosition !== undefined) {
      // Defer scroll restoration to next frame to allow CodeMirror to update first
      requestAnimationFrame(() => {
        if (scrollRef.current) {
          isRestoringRef.current = true;
          scrollRef.current.scrollTop = scrollPosition;
          // Reset flag after a short delay to allow scroll event to be ignored
          setTimeout(() => {
            isRestoringRef.current = false;
            hasRestoredRef.current = true; // Mark that first restoration is complete
          }, 50);
        }
      });
    }
  }, [scrollPosition]);

  // Handle scroll events - wrapped in useCallback for stability
  const handleScroll = useCallback(
    (e: React.UIEvent<HTMLDivElement>) => {
      // Ignore scroll events during restoration to prevent feedback loop
      if (isRestoringRef.current) {
        return;
      }

      // Ignore initial scroll events before first restoration completes
      // This prevents CodeMirror's initial scroll event from overwriting saved position
      if (!hasRestoredRef.current) {
        return;
      }

      if (onScrollPositionChange) {
        const scrollTop = e.currentTarget.scrollTop;
        onScrollPositionChange(scrollTop);
      }
    },
    [onScrollPositionChange],
  );

  return {
    scrollRef,
    handleScroll,
  };
};
