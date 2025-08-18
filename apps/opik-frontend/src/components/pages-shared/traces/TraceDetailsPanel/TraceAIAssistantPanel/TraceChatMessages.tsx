import React, { useEffect, useRef, useCallback } from "react";
import { ChatLLMessage } from "@/types/llm";
import TraceChatMessage from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAIAssistantPanel/TraceChatMessage";

type TraceChatMessagesProps = {
  messages: ChatLLMessage[];
  onCopyToInput?: (content: string) => void;
};

const TraceChatMessages: React.FC<TraceChatMessagesProps> = ({
  messages,
  onCopyToInput,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const currentScrollRef = useRef(0);

  useEffect(() => {
    if (!scrollContainerRef.current) return;
    const extraSpace = 60;
    if (
      scrollContainerRef.current.scrollTop +
        scrollContainerRef.current.clientHeight +
        extraSpace >=
        scrollContainerRef.current.scrollHeight &&
      scrollContainerRef.current.scrollHeight > currentScrollRef.current
    ) {
      currentScrollRef.current = scrollContainerRef.current.scrollHeight;
      scrollContainerRef.current.scrollTo({
        top: scrollContainerRef.current.scrollHeight,
        behavior: "smooth",
      });
    }
  }, [messages]);

  const handleCopy = useCallback(
    (content: string) => onCopyToInput?.(content),
    [onCopyToInput],
  );

  return (
    <div
      className="flex size-full justify-center overflow-y-auto"
      ref={scrollContainerRef}
    >
      <div className="flex w-[768px] flex-col gap-2">
        {messages.map((m) => (
          <TraceChatMessage key={m.id} message={m} onCopyToInput={handleCopy} />
        ))}
      </div>
    </div>
  );
};

export default TraceChatMessages;
