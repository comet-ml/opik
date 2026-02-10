import { useState, useCallback, useRef } from "react";
import { BASE_API_URL } from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/llm";
import type { ViewTree, ViewPatch } from "@/lib/data-view";
import {
  applyPatch,
  createEmptyTree,
  parseViewStreamGraceful,
} from "@/lib/data-view";
import type { LLMPromptConfigsType } from "@/types/providers";

// ============================================================================
// TYPES
// ============================================================================

export interface StreamingGenerateParams {
  /** Model to use for generation */
  model: string;
  /** User message describing what to generate */
  userMessage: string;
  /** System prompt with JSONL instructions */
  systemPrompt: string;
  /** Optional LLM configs (temperature, etc.) */
  configs?: Partial<LLMPromptConfigsType>;
  /** Optional initial tree for updates (patches will be applied to this tree) */
  initialTree?: ViewTree;
  /** Callback fired for each patch received */
  onPatch?: (patch: ViewPatch, tree: ViewTree) => void;
  /** Callback fired when streaming completes */
  onComplete?: (tree: ViewTree) => void;
  /** Callback fired on error */
  onError?: (error: Error) => void;
}

export interface UseStreamingTreeCompletionParams {
  workspaceName: string;
}

export interface UseStreamingTreeCompletionReturn {
  /** Current tree being built */
  tree: ViewTree;
  /** Whether currently streaming */
  isStreaming: boolean;
  /** Error message if any */
  error: string | null;
  /** Number of patches received */
  patchCount: number;
  /** Generate a tree from streaming JSONL */
  generate: (params: StreamingGenerateParams) => Promise<ViewTree | null>;
  /** Abort current generation */
  abort: () => void;
  /** Reset state */
  reset: () => void;
}

// ============================================================================
// HOOK IMPLEMENTATION
// ============================================================================

const useStreamingTreeCompletion = ({
  workspaceName,
}: UseStreamingTreeCompletionParams): UseStreamingTreeCompletionReturn => {
  const [tree, setTree] = useState<ViewTree>(createEmptyTree());
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [patchCount, setPatchCount] = useState(0);

  const abortControllerRef = useRef<AbortController | null>(null);

  const reset = useCallback(() => {
    setTree(createEmptyTree());
    setIsStreaming(false);
    setError(null);
    setPatchCount(0);
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
  }, []);

  const abort = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
  }, []);

  const generate = useCallback(
    async ({
      model,
      userMessage,
      systemPrompt,
      configs,
      initialTree,
      onPatch,
      onComplete,
      onError,
    }: StreamingGenerateParams): Promise<ViewTree | null> => {
      // Reset state
      setIsStreaming(true);
      setError(null);
      setPatchCount(0);

      // Create abort controller
      abortControllerRef.current = new AbortController();

      // Start with initial tree (for updates) or empty tree (for new generation)
      let currentTree = initialTree ?? createEmptyTree();
      setTree(currentTree);

      // Build messages
      const messages = [
        { role: "system", content: systemPrompt },
        { role: "user", content: userMessage },
      ];

      // Extract max tokens from configs
      const configMaxTokens =
        configs && "maxCompletionTokens" in configs
          ? configs.maxCompletionTokens
          : configs && "maxTokens" in configs
            ? configs.maxTokens
            : undefined;

      try {
        const requestBody = snakeCaseObj({
          model,
          messages,
          stream: true, // Enable streaming!
          maxCompletionTokens:
            configMaxTokens ?? DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
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
            signal: abortControllerRef.current.signal,
          },
        );

        if (!response.ok) {
          const errorData = await response.json().catch(() => ({}));
          throw new Error(
            errorData.message ||
              errorData.errors?.join(" ") ||
              `Request failed with status ${response.status}`,
          );
        }

        if (!response.body) {
          throw new Error("No response body for streaming");
        }

        // Process the raw NDJSON stream and extract JSONL content
        let patchesReceived = 0;
        const contentGenerator = extractStreamContent(response.body);
        const contentStream = generatorToStream(contentGenerator);

        console.log("[Stream] Starting to parse patches...");
        for await (const patch of parseViewStreamGraceful(contentStream)) {
          console.log("[Stream] Got patch:", patch);
          // Apply patch to tree
          currentTree = applyPatch(currentTree, patch);
          patchesReceived++;

          // Update state
          setTree(currentTree);
          setPatchCount(patchesReceived);

          // Call callback
          onPatch?.(patch, currentTree);
        }
        console.log(
          "[Stream] Finished parsing, patches received:",
          patchesReceived,
        );

        setIsStreaming(false);
        onComplete?.(currentTree);
        return currentTree;
      } catch (err) {
        if ((err as Error).name === "AbortError") {
          // User cancelled - don't treat as error
          setIsStreaming(false);
          return currentTree;
        }

        const errorMessage =
          err instanceof Error ? err.message : "Unknown error";
        setError(errorMessage);
        setIsStreaming(false);
        onError?.(err as Error);
        return null;
      }
    },
    [workspaceName],
  );

  return {
    tree,
    isStreaming,
    error,
    patchCount,
    generate,
    abort,
    reset,
  };
};

// ============================================================================
// STREAM CONTENT EXTRACTION (NDJSON FORMAT)
// ============================================================================

/**
 * Extract text content from OpenAI-style streaming response.
 * Handles raw NDJSON format (not SSE) that backend sends.
 * Yields content strings from choices[0].delta.content.
 */
async function* extractStreamContent(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<string, void, unknown> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        // Process remaining buffer
        const trimmed = buffer.trim();
        if (trimmed) {
          const content = parseResponseLine(trimmed);
          if (content) yield content;
        }
        break;
      }

      buffer += decoder.decode(value, { stream: true });

      // Split on both \n and \r\n (backend uses \r\n)
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        const content = parseResponseLine(trimmed);
        if (content) {
          console.log("[Stream] Content chunk:", JSON.stringify(content));
          yield content;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/**
 * Parse a single response line and extract content.
 * Handles both raw JSON and SSE-prefixed formats for compatibility.
 */
function parseResponseLine(line: string): string | null {
  // Skip SSE comments
  if (line.startsWith(":")) return null;

  // Handle SSE format if present (for compatibility)
  let jsonStr = line;
  if (line.startsWith("data: ")) {
    jsonStr = line.slice(6);
  }

  // Skip [DONE] marker
  if (jsonStr === "[DONE]") return null;

  console.log("[Stream] Raw line:", jsonStr);

  try {
    const parsed = JSON.parse(jsonStr);
    return parsed.choices?.[0]?.delta?.content || null;
  } catch {
    // Not valid JSON, skip
    console.warn("[Stream] Invalid JSON line:", jsonStr);
    return null;
  }
}

/**
 * Convert async generator of strings to ReadableStream<Uint8Array>.
 */
function generatorToStream(
  generator: AsyncGenerator<string, void, unknown>,
): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    async pull(controller) {
      const { done, value } = await generator.next();
      if (done) {
        controller.close();
      } else if (value) {
        controller.enqueue(encoder.encode(value));
      }
    },
  });
}

export default useStreamingTreeCompletion;
