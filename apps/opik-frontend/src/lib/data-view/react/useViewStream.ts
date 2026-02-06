import { useState, useCallback, useRef, useEffect } from "react";
import type { ViewTree, ViewPatch } from "../core/types";
import { parseViewStream } from "../core/streaming";
import { createEmptyTree, applyPatch, mergeViewTrees } from "../core/patches";

async function getStream(
  source: string | Response | ReadableStream,
  signal: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  if (source instanceof ReadableStream) return source;
  if (source instanceof Response) {
    if (!source.body) throw new Error("Response has no body");
    return source.body;
  }
  const response = await fetch(source, { signal });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  if (!response.body) throw new Error("Response has no body");
  return response.body;
}

// ============================================================================
// HOOK
// ============================================================================

export interface UseViewStreamOptions {
  /** Existing tree to patch (for edit mode) */
  existingTree?: ViewTree;

  /** Props to preserve from user edits */
  preserveProps?: string[];

  /** Called on each patch */
  onPatch?: (patch: ViewPatch, tree: ViewTree) => void;

  /** Called on completion */
  onComplete?: (tree: ViewTree) => void;

  /** Called on error */
  onError?: (error: Error) => void;
}

export interface UseViewStreamResult {
  tree: ViewTree;
  isStreaming: boolean;
  error: Error | null;
  patchCount: number;
  startStream: (source: string | Response | ReadableStream) => Promise<void>;
  abort: () => void;
  reset: () => void;
}

export function useViewStream(
  options: UseViewStreamOptions = {},
): UseViewStreamResult {
  const {
    existingTree,
    preserveProps = ["title", "label", "name"], // Default editable props
    onPatch,
    onComplete,
    onError,
  } = options;

  const [tree, setTree] = useState<ViewTree>(existingTree ?? createEmptyTree());
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [patchCount, setPatchCount] = useState(0);
  const abortRef = useRef<AbortController | null>(null);

  const abort = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setIsStreaming(false);
  }, []);

  const reset = useCallback(() => {
    abort();
    setTree(existingTree ?? createEmptyTree());
    setError(null);
    setPatchCount(0);
  }, [abort, existingTree]);

  const startStream = useCallback(
    async (source: string | Response | ReadableStream) => {
      abort();
      setError(null);
      setIsStreaming(true);
      setPatchCount(0);

      const controller = new AbortController();
      abortRef.current = controller;
      let currentTree = existingTree ?? createEmptyTree();
      let count = 0;

      try {
        const stream = await getStream(source, controller.signal);

        for await (const patch of parseViewStream(stream)) {
          if (controller.signal.aborted) break;

          count++;
          currentTree = existingTree
            ? mergeViewTrees(currentTree, [patch], { preserveProps })
            : applyPatch(currentTree, patch);

          setTree(currentTree);
          setPatchCount(count);
          onPatch?.(patch, currentTree);
        }

        if (!controller.signal.aborted) onComplete?.(currentTree);
      } catch (err) {
        if ((err as Error).name !== "AbortError") {
          setError(err as Error);
          onError?.(err as Error);
        }
      } finally {
        setIsStreaming(false);
        abortRef.current = null;
      }
    },
    [abort, existingTree, preserveProps, onPatch, onComplete, onError],
  );

  // Cleanup on unmount
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  return { tree, isStreaming, error, patchCount, startStream, abort, reset };
}
