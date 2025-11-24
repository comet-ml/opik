import { useEffect, useRef, useState } from "react";
import { LLMMessage } from "@/types/llm";

interface UseRawJsonViewReturn {
  rawJsonValue: string;
  setRawJsonValue: (value: string) => void;
}

export const useRawJsonView = (
  messages: LLMMessage[],
  showRawView: boolean,
): UseRawJsonViewReturn => {
  const [rawJsonValue, setRawJsonValue] = useState("");
  const prevShowRawView = useRef(showRawView);

  // Sync raw JSON value when switching to raw view
  useEffect(() => {
    // Only update rawJsonValue when switching TO raw view (not while already in raw view)
    if (showRawView && !prevShowRawView.current) {
      setRawJsonValue(
        JSON.stringify(
          messages.map((m) => ({
            role: m.role,
            content: m.content,
          })),
          null,
          2,
        ),
      );
    }
    prevShowRawView.current = showRawView;
  }, [showRawView, messages]);

  return { rawJsonValue, setRawJsonValue };
};
