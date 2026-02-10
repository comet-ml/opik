import type { ViewPatch, ViewTree } from "./types";
import { ViewPatchSchema } from "./types";
import { applyPatch, createEmptyTree } from "./patches";

// ============================================================================
// STREAM PARSING
// ============================================================================

/**
 * Parse NDJSON (newline-delimited JSON) stream into patches.
 * Each line is a ViewPatch object.
 *
 * @example
 * const stream = response.body;
 * for await (const patch of parseViewStream(stream)) {
 *   tree = applyPatch(tree, patch);
 *   onUpdate(tree);
 * }
 */
export async function* parseViewStream(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<ViewPatch, void, unknown> {
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
          yield parsePatchLine(trimmed);
        }
        break;
      }

      buffer += decoder.decode(value, { stream: true });

      // Process complete lines
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed) {
          yield parsePatchLine(trimmed);
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

function parsePatchLine(line: string): ViewPatch {
  const parsed = JSON.parse(line);
  const result = ViewPatchSchema.safeParse(parsed);

  if (!result.success) {
    throw new Error(`Invalid patch: ${result.error.message}`);
  }

  return result.data;
}

/**
 * Safely parse a single line into a ViewPatch.
 * Returns null for invalid lines instead of throwing.
 */
function parsePatchLineSafe(line: string): ViewPatch | null {
  try {
    const parsed = JSON.parse(line);
    const result = ViewPatchSchema.safeParse(parsed);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Parse NDJSON (newline-delimited JSON) stream into patches.
 * Gracefully skips invalid lines instead of throwing.
 *
 * @example
 * const stream = response.body;
 * for await (const patch of parseViewStreamGraceful(stream)) {
 *   tree = applyPatch(tree, patch);
 *   onUpdate(tree);
 * }
 */
export async function* parseViewStreamGraceful(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<ViewPatch, void, unknown> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let chunkCount = 0;

  console.log("[JSONL] parseViewStreamGraceful started");

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        console.log("[JSONL] Stream done, remaining buffer:", buffer);
        // Process remaining buffer
        const trimmed = buffer.trim();
        if (trimmed) {
          // Handle markdown-wrapped content
          if (trimmed.startsWith("```")) {
            console.log("[JSONL] Skipping markdown fence in final buffer");
          } else {
            const patch = parsePatchLineSafe(trimmed);
            if (patch) {
              yield patch;
            } else {
              console.warn("[JSONL] Skipping invalid patch line:", trimmed);
            }
          }
        }
        break;
      }

      chunkCount++;
      const decoded = decoder.decode(value, { stream: true });
      console.log(`[JSONL] Chunk ${chunkCount}:`, JSON.stringify(decoded));
      buffer += decoded;

      // Process complete lines
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed) {
          // Skip markdown code fences
          if (trimmed.startsWith("```")) {
            console.log("[JSONL] Skipping markdown fence:", trimmed);
            continue;
          }
          const patch = parsePatchLineSafe(trimmed);
          if (patch) {
            console.log("[JSONL] Valid patch found:", patch);
            yield patch;
          } else {
            console.warn("[JSONL] Skipping invalid patch line:", trimmed);
          }
        }
      }
    }
  } finally {
    console.log(
      `[JSONL] parseViewStreamGraceful finished, total chunks: ${chunkCount}`,
    );
    reader.releaseLock();
  }
}

// ============================================================================
// STREAM PROCESSOR
// ============================================================================

export interface StreamProcessorOptions {
  /** Initial tree (for patching existing views) */
  initialTree?: ViewTree;

  /** Called after each patch */
  onPatch?: (patch: ViewPatch, tree: ViewTree) => void;

  /** Called on completion */
  onComplete?: (tree: ViewTree) => void;

  /** Called on error */
  onError?: (error: Error) => void;
}

/**
 * Process a stream of patches and return the final tree.
 */
export async function processViewStream(
  stream: ReadableStream<Uint8Array>,
  options: StreamProcessorOptions = {},
): Promise<ViewTree> {
  const {
    initialTree = createEmptyTree(),
    onPatch,
    onComplete,
    onError,
  } = options;

  let tree = initialTree;

  try {
    for await (const patch of parseViewStream(stream)) {
      tree = applyPatch(tree, patch);
      onPatch?.(patch, tree);
    }
    onComplete?.(tree);
    return tree;
  } catch (error) {
    onError?.(error as Error);
    throw error;
  }
}

// ============================================================================
// TREE TO PATCHES (for initial generation)
// ============================================================================

/**
 * Convert a complete tree to patches.
 * Used when backend returns full JSON and you want to stream-render it.
 */
export function treeToPatches(tree: ViewTree): ViewPatch[] {
  const patches: ViewPatch[] = [
    { op: "add", path: "/version", value: tree.version },
    { op: "add", path: "/root", value: tree.root },
    { op: "add", path: "/nodes", value: {} },
    ...Object.entries(tree.nodes).map(([id, node]) => ({
      op: "add" as const,
      path: `/nodes/${id}`,
      value: node,
    })),
  ];

  if (tree.meta) {
    patches.push({ op: "add", path: "/meta", value: tree.meta });
  }

  return patches;
}
