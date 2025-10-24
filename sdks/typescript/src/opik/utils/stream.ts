import { TextDecoder } from "util";
import * as core from "@/rest_api/core";
import { logger } from "./logger";

/**
 * Parses an NDJSON stream into an array of typed objects
 *
 * @param stream The async iterable stream of bytes
 * @param serializer Schema for deserializing the stream data (can be passed as ExperimentItemCompare, etc.)
 * @param nbSamples Optional maximum number of samples to parse
 * @returns Array of parsed objects
 */
export async function parseNdjsonStreamToArray<T>(
  stream: AsyncIterable<Uint8Array>,
  serializer: core.serialization.Schema<unknown, T>,
  nbSamples?: number
): Promise<T[]> {
  // Early return for nbSamples=0 to avoid processing any items
  if (nbSamples === 0) {
    return [];
  }

  const decoder = new TextDecoder("utf-8");
  const results: T[] = [];
  let buffer = "";

  try {
    for await (const chunk of stream) {
      buffer += decoder.decode(chunk, { stream: true });
      const lines = buffer.split("\n");

      buffer = lines.pop() ?? "";

      for (const line of lines) {
        if (!line.trim() || line === "{}" || line === "[]" || line === "null") {
          continue;
        }

        try {
          const parsed = JSON.parse(line);
          const result = serializer.parse(parsed);

          if (result.ok) {
            results.push(result.value);

            if (nbSamples !== undefined && results.length >= nbSamples) {
              return results;
            }
          } else {
            logger.error("Error parsing experiment item:", result.errors);
          }
        } catch (parseError) {
          logger.error(
            "Error parsing JSON line:",
            parseError instanceof Error
              ? parseError.message
              : String(parseError)
          );
        }
      }
    }

    if (
      buffer.trim() &&
      buffer !== "{}" &&
      buffer !== "[]" &&
      buffer !== "null"
    ) {
      try {
        const parsed = JSON.parse(buffer);
        const result = serializer.parse(parsed);

        if (result.ok) {
          results.push(result.value);
        } else {
          logger.error("Error parsing experiment item:", result.errors);
        }
      } catch (parseError) {
        logger.error(
          "Error parsing remaining buffer:",
          parseError instanceof Error ? parseError.message : String(parseError)
        );
      }
    }
  } catch (err) {
    logger.error(
      "Error processing stream:",
      err instanceof Error ? err.message : String(err)
    );
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
