import { useCallback, useState } from "react";
import { LLMMessage } from "@/types/llm";

interface UseRawJsonViewReturn {
  rawJsonValue: string;
  setRawJsonValue: (value: string) => void;
  syncRawJsonFromMessages: () => void;
}

export const useRawJsonView = (
  messages: LLMMessage[],
): UseRawJsonViewReturn => {
  const [rawJsonValue, setRawJsonValue] = useState("");

  // Callback to sync raw JSON value from messages
  const syncRawJsonFromMessages = useCallback(() => {
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
  }, [messages]);

  return { rawJsonValue, setRawJsonValue, syncRawJsonFromMessages };
};
