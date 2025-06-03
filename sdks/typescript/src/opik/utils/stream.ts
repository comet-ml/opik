import { TextDecoder } from "util";

export async function parseNdjsonStreamToArray<T>(
  stream: AsyncIterable<Uint8Array>,
  nbSamples?: number
): Promise<T[]> {
  const decoder = new TextDecoder("utf-8");
  const results: T[] = [];
  let buffer = "";

  try {
    for await (const chunk of stream) {
      buffer += decoder.decode(chunk, { stream: true });
      const lines = buffer.split("\n");

      // Keep any incomplete line in the buffer
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        if (!line.trim()) continue;
        const parsed = JSON.parse(line) as T;
        results.push(parsed);
        if (nbSamples !== undefined && results.length >= nbSamples) {
          return results;
        }
      }
    }

    // Handle remaining buffered content
    if (buffer.trim()) {
      const parsed = JSON.parse(buffer) as T;
      results.push(parsed);
    }
  } catch (err) {
    console.error("Error parsing stream:", err);
  }

  return results;
}

export function splitIntoBatches<T>(
  items: T[],
  options: { maxBatchSize: number }
): T[][] {
  const batches: T[][] = [];
  for (let i = 0; i < items.length; i += options.maxBatchSize) {
    batches.push(items.slice(i, i + options.maxBatchSize));
  }
  return batches;
}
