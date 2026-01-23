import { useState, useCallback } from "react";
import { BASE_API_URL } from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { ToolCall } from "@/types/schema-proposal";

/**
 * Message in the conversation
 */
export interface ConversationalMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

/**
 * Parameters for generating a response
 */
export interface GenerateConversationalParams {
  model: PROVIDER_MODEL_TYPE | string;
  userMessage: string;
  systemPrompt?: string;
  context?: Record<string, unknown>;
  configs?: Partial<LLMPromptConfigsType>;
  tools?: unknown[]; // Tool definitions
}

/**
 * Response from the conversational AI
 */
export interface ConversationalResponse {
  content: string;
  toolCalls?: ToolCall[];
}

/**
 * Hook parameters
 */
export interface UseConversationalAIParams {
  workspaceName: string;
}

/**
 * Hook return type
 */
export interface UseConversationalAIReturn {
  messages: ConversationalMessage[];
  isLoading: boolean;
  error: string | null;
  generate: (params: GenerateConversationalParams) => Promise<ConversationalResponse | null>;
  reset: () => void;
}

/**
 * Conversational AI hook with tools support
 * Used for the Chat AI that can propose schema changes via tool calls
 */
const useConversationalAI = ({
  workspaceName,
}: UseConversationalAIParams): UseConversationalAIReturn => {
  const [messages, setMessages] = useState<ConversationalMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setMessages([]);
    setError(null);
  }, []);

  const generate = useCallback(
    async ({
      model,
      userMessage,
      systemPrompt,
      configs,
      tools,
    }: GenerateConversationalParams): Promise<ConversationalResponse | null> => {
      setIsLoading(true);
      setError(null);

      // Build messages array
      const requestMessages: ConversationalMessage[] = [];

      // Add system prompt if provided
      if (systemPrompt) {
        requestMessages.push({
          role: "system",
          content: systemPrompt,
        });
      }

      // Add conversation history
      requestMessages.push(...messages);

      // Add new user message
      const newUserMessage: ConversationalMessage = {
        role: "user",
        content: userMessage,
      };
      requestMessages.push(newUserMessage);

      // Add user message to state immediately
      setMessages((prev) => [...prev, newUserMessage]);

      try {
        const requestBody = snakeCaseObj({
          model,
          messages: requestMessages,
          stream: false,
          tools: tools || undefined,
          ...configs,
        });

        const response = await fetch(
          `${BASE_API_URL}/v1/private/chat/completions`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "Comet-Workspace": workspaceName,
            },
            body: JSON.stringify(requestBody),
            credentials: "include",
          },
        );

        if (!response.ok) {
          const errorData = await response.json().catch(() => ({}));
          throw new Error(
            errorData.message ||
              errorData.errors?.join(" ") ||
              "Request failed",
          );
        }

        const data = await response.json();
        const choice = data.choices?.[0];

        if (!choice) {
          throw new Error("No response from AI");
        }

        const assistantMessage = choice.message;
        const content = assistantMessage.content || "";
        const toolCalls = assistantMessage.tool_calls as ToolCall[] | undefined;

        // Only add assistant message to history if it has content
        // (tool calls with empty content are handled by the UI separately)
        if (content) {
          setMessages((prev) => [
            ...prev,
            { role: "assistant", content },
          ]);
        }

        setIsLoading(false);
        return { content, toolCalls };
      } catch (err) {
        const errorMessage =
          err instanceof Error ? err.message : "Unknown error";
        setError(errorMessage);
        setIsLoading(false);
        return null;
      }
    },
    [workspaceName, messages],
  );

  return {
    messages,
    isLoading,
    error,
    generate,
    reset,
  };
};

export default useConversationalAI;
