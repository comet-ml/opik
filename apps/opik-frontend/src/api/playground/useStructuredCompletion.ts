import { useState, useCallback } from "react";
import { z } from "zod";

import { BASE_API_URL } from "@/api/api";
import { safelyParseJSON, snakeCaseObj } from "@/lib/utils";
import { convertZodToOpenAIFormat } from "@/lib/zodSchema";
import { renderTemplate, flattenContextPaths } from "@/lib/templateUtils";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/llm";
import {
  UseStructuredCompletionParams,
  UseStructuredCompletionReturn,
  StructuredCompletionMessage,
  GenerateParams,
} from "@/types/structured-completion";

const useStructuredCompletion = <T extends z.ZodSchema>({
  schema,
  workspaceName,
}: UseStructuredCompletionParams<T>): UseStructuredCompletionReturn<
  z.infer<T>
> => {
  const [result, setResult] = useState<z.infer<T> | null>(null);
  const [messages, setMessages] = useState<StructuredCompletionMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [usage, setUsage] =
    useState<UseStructuredCompletionReturn<z.infer<T>>["usage"]>(null);

  const addMessage = useCallback((message: StructuredCompletionMessage) => {
    setMessages((prev) => [...prev, message]);
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  const reset = useCallback(() => {
    setResult(null);
    setMessages([]);
    setError(null);
    setUsage(null);
  }, []);

  const generate = useCallback(
    async ({
      model,
      userMessage,
      systemPrompt,
      context,
      maxCompletionTokens,
      configs,
    }: GenerateParams) => {
      setIsLoading(true);
      setError(null);

      // Flatten context for nested path access (e.g., {{trace.input.messages}})
      const flatContext = context ? flattenContextPaths(context) : {};

      // Build messages array with template rendering
      const requestMessages: StructuredCompletionMessage[] = [];

      // Add system prompt if provided (with context rendering)
      if (systemPrompt) {
        requestMessages.push({
          role: "system",
          content: renderTemplate(systemPrompt, flatContext),
        });
      }

      // Add conversation history (already rendered)
      requestMessages.push(...messages);

      // Add new user message (with context rendering)
      const renderedUserMessage = renderTemplate(userMessage, flatContext);
      const newUserMessage: StructuredCompletionMessage = {
        role: "user",
        content: renderedUserMessage,
      };
      requestMessages.push(newUserMessage);

      // Add user message to state immediately
      setMessages((prev) => [...prev, newUserMessage]);

      try {
        const responseFormat = convertZodToOpenAIFormat(schema);

        // Extract maxCompletionTokens from configs if available
        const configMaxTokens =
          configs && "maxCompletionTokens" in configs
            ? configs.maxCompletionTokens
            : configs && "maxTokens" in configs
              ? configs.maxTokens
              : undefined;

        const requestBody = snakeCaseObj({
          model,
          messages: requestMessages,
          stream: false, // Required for structured output
          responseFormat: responseFormat,
          maxCompletionTokens:
            maxCompletionTokens ??
            configMaxTokens ??
            DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
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
        const content = data.choices?.[0]?.message?.content;

        if (!content) {
          throw new Error("No content in response");
        }

        // Parse and validate with Zod
        const parsed = safelyParseJSON(content);
        const validated = schema.parse(parsed);

        // Update state
        setResult(validated);
        setUsage(data.usage ?? null);

        // Add assistant message to history (raw JSON string)
        setMessages((prev) => [
          ...prev,
          { role: "assistant", content }, // Raw JSON string
        ]);

        setIsLoading(false);
        return validated;
      } catch (err) {
        const errorMessage =
          err instanceof z.ZodError
            ? `Validation error: ${err.errors.map((e) => e.message).join(", ")}`
            : err instanceof Error
              ? err.message
              : "Unknown error";

        setError(errorMessage);
        setIsLoading(false);
        return null;
      }
    },
    [schema, workspaceName, messages],
  );

  return {
    result,
    messages,
    isLoading,
    error,
    usage,
    generate,
    addMessage,
    clearMessages,
    reset,
  };
};

export default useStructuredCompletion;
