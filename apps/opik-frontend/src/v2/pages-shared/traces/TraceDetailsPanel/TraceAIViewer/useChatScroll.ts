import { useCallback, useEffect, useRef, useState } from "react";

const SCROLL_THRESHOLD = 50; // pixels from bottom to consider "at bottom"

interface UseChatScrollProps {
  /**
   * Content to track for changes - can be array length or string length
   */
  contentLength: number;
  /**
   * Whether content is actively being updated (streaming/loading)
   */
  isStreaming: boolean;
  /**
   * Whether to use smooth scrolling animation (default: true)
   */
  smooth?: boolean;
}

export const useChatScroll = ({
  contentLength,
  isStreaming,
  smooth = true,
}: UseChatScrollProps) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const prevContentLengthRef = useRef<number>(0);
  const [isAutoScrollEnabled, setIsAutoScrollEnabled] = useState(true);

  useEffect(() => {
    const scrollContainer = scrollContainerRef.current;
    if (!scrollContainer) return;

    const prevContentLength = prevContentLengthRef.current;
    const hasNewContent = contentLength > prevContentLength;
    const hasContentChangedFromEmpty =
      prevContentLength === 0 && contentLength > 0;
    prevContentLengthRef.current = contentLength;

    // Only scroll if there's new content and auto-scroll is enabled
    if (!hasNewContent || !isAutoScrollEnabled) return;

    // Use requestAnimationFrame to ensure DOM has updated
    requestAnimationFrame(() => {
      if (!scrollContainer) return;
      // Use instant scroll when content first appears, smooth otherwise
      const shouldUseSmooth = smooth && !hasContentChangedFromEmpty;
      scrollContainer.scrollTo({
        top: scrollContainer.scrollHeight,
        behavior: shouldUseSmooth ? "smooth" : "auto",
      });
    });
  }, [contentLength, isStreaming, isAutoScrollEnabled, smooth]);

  const handleScroll = useCallback(() => {
    if (!scrollContainerRef.current) return;

    const { scrollTop, scrollHeight, clientHeight } =
      scrollContainerRef.current;
    const isAtBottom =
      scrollHeight - scrollTop - clientHeight < SCROLL_THRESHOLD;

    setIsAutoScrollEnabled(isAtBottom);
  }, []);

  const scrollToBottom = useCallback(() => {
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollTo({
        top: scrollContainerRef.current.scrollHeight,
        behavior: smooth ? "smooth" : "auto",
      });
      setIsAutoScrollEnabled(true);
    }
  }, [smooth]);

  return {
    scrollContainerRef,
    isAutoScrollEnabled,
    handleScroll,
    scrollToBottom,
  };
};
