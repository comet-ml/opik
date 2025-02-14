import React, { useEffect, useRef } from "react";
import { ChatLLMessage } from "@/types/llm";
import ChatMessage from "@/components/pages/ChatPage/ChatMessage";
import { useScrollKey } from "@/store/ChatStore";

type ChatMessagesProps = {
  messages: ChatLLMessage[];
};

const ChatMessages: React.FC<ChatMessagesProps> = ({ messages }) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const currentScrollRef = useRef(0);
  const scrollKey = useScrollKey();

  useEffect(() => {
    if (!scrollContainerRef.current) return;
    const extraSpace = 60;

    // scroll to bottom
    // - if scrolled key is set to 0
    // - if container is almost/completely scrolled to bottom
    if (
      !scrollKey ||
      (scrollContainerRef.current.scrollTop +
        scrollContainerRef.current.clientHeight +
        extraSpace >=
        scrollContainerRef.current.scrollHeight &&
        scrollContainerRef.current.scrollHeight > currentScrollRef.current)
    ) {
      currentScrollRef.current = scrollContainerRef.current.scrollHeight;
      scrollContainerRef.current.scrollTo({
        top: scrollContainerRef.current.scrollHeight,
        behavior: "smooth",
      });
    }
  }, [scrollKey]);

  return (
    <div
      className="flex size-full justify-center overflow-y-auto"
      ref={scrollContainerRef}
    >
      <div className="flex w-[768px] flex-col gap-2">
        {messages.map((m) => (
          <ChatMessage key={m.id} message={m} />
        ))}
      </div>
    </div>
  );
};

export default ChatMessages;
