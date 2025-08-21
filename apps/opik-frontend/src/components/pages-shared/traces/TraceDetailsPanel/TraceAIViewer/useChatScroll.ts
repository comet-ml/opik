import { useEffect, useRef } from "react";
import { TraceAnalyzerLLMMessage } from "@/types/ai-assistant";

interface UseChatScrollProps {
  messages: TraceAnalyzerLLMMessage[];
  isStreaming: boolean;
}

export const useChatScroll = ({
  messages,
  isStreaming,
}: UseChatScrollProps) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const prevMessagesLengthRef = useRef<number>(0);

  useEffect(() => {
    const scrollContainer = scrollContainerRef.current;
    if (!scrollContainer) return;

    requestAnimationFrame(() => {
      if (!scrollContainer) return;

      const { scrollTop, scrollHeight, clientHeight } = scrollContainer;
      const isNearBottom = scrollHeight - scrollTop - clientHeight < 50;

      const prevMessagesLength = prevMessagesLengthRef.current;
      const hasMessagesChangedFromEmpty =
        prevMessagesLength === 0 && messages.length > 0;

      // Auto scroll to bottom if messages changed from empty to having content
      // or if user is near bottom and new messages arrive
      if (hasMessagesChangedFromEmpty || isNearBottom) {
        scrollContainer.scrollTo({
          top: scrollHeight,
          behavior: hasMessagesChangedFromEmpty ? "auto" : "smooth",
        });
      }

      prevMessagesLengthRef.current = messages.length;
    });
  }, [messages, isStreaming]);

  return {
    scrollContainerRef,
  };
};
