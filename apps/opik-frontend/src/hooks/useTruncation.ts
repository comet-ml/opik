import { useState, useRef, useEffect } from "react";

interface UseTruncationReturn {
  ref: React.RefObject<HTMLDivElement>;
  isTruncated: boolean;
  isExpanded: boolean;
  toggle: () => void;
}

/**
 * Custom hook to detect if content is truncated and manage expand/collapse state
 *
 * @param maxLines - Maximum number of lines before truncation
 * @param enabled - Whether truncation detection is enabled
 * @returns Object containing ref, truncation state, expansion state, and toggle function
 */
export const useTruncation = (
  maxLines: number,
  enabled: boolean,
): UseTruncationReturn => {
  const [isTruncated, setIsTruncated] = useState(false);
  const [isExpanded, setIsExpanded] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!enabled || !ref.current) {
      return;
    }

    const element = ref.current;
    const lineHeight = parseInt(
      window.getComputedStyle(element).lineHeight || "20",
      10,
    );
    const maxHeight = lineHeight * maxLines;

    setIsTruncated(element.scrollHeight > maxHeight);
  }, [enabled, maxLines]);

  const toggle = () => {
    setIsExpanded((prev) => !prev);
  };

  return {
    ref,
    isTruncated,
    isExpanded,
    toggle,
  };
};
