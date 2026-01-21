import get from "lodash/get";
import { Trace } from "@/types/traces";

/**
 * Resolve a path in a trace object using dot notation and array indexing.
 * Supports nested paths like 'input.messages[0].content'
 *
 * @param trace - The trace object to resolve the path in
 * @param path - Dot notation path (e.g., 'input.messages[0].content')
 * @returns The resolved value or undefined if path doesn't exist
 */
export const resolveTracePath = (
  trace: Trace | null | undefined,
  path: string,
): unknown => {
  if (!trace || !path) {
    return undefined;
  }

  try {
    // lodash/get handles both dot notation and array indexing
    return get(trace, path);
  } catch (error) {
    console.warn(`Failed to resolve path "${path}" in trace:`, error);
    return undefined;
  }
};

/**
 * Check if a path exists in the trace object
 *
 * @param trace - The trace object to check
 * @param path - Dot notation path
 * @returns true if the path exists and has a value
 */
export const tracePathExists = (
  trace: Trace | null | undefined,
  path: string,
): boolean => {
  const value = resolveTracePath(trace, path);
  return value !== undefined && value !== null;
};

/**
 * Determine if a value is likely a URL
 *
 * @param value - The value to check
 * @returns true if the value looks like a URL
 */
export const isUrl = (value: unknown): boolean => {
  if (typeof value !== "string") return false;

  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
};

/**
 * Determine if a value is likely an image URL
 *
 * @param value - The value to check
 * @returns true if the value looks like an image URL
 */
export const isImageUrl = (value: unknown): boolean => {
  if (!isUrl(value)) return false;

  const imageExtensions = [".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg"];
  const url = value as string;

  return imageExtensions.some((ext) => url.toLowerCase().includes(ext));
};

/**
 * Determine if a value is likely a video URL
 *
 * @param value - The value to check
 * @returns true if the value looks like a video URL
 */
export const isVideoUrl = (value: unknown): boolean => {
  if (!isUrl(value)) return false;

  const videoExtensions = [".mp4", ".webm", ".ogg", ".mov"];
  const url = value as string;

  return videoExtensions.some((ext) => url.toLowerCase().includes(ext));
};

/**
 * Determine if a value is likely an audio URL
 *
 * @param value - The value to check
 * @returns true if the value looks like an audio URL
 */
export const isAudioUrl = (value: unknown): boolean => {
  if (!isUrl(value)) return false;

  const audioExtensions = [".mp3", ".wav", ".ogg", ".m4a"];
  const url = value as string;

  return audioExtensions.some((ext) => url.toLowerCase().includes(ext));
};

/**
 * Determine if a value is likely a PDF URL
 *
 * @param value - The value to check
 * @returns true if the value looks like a PDF URL
 */
export const isPdfUrl = (value: unknown): boolean => {
  if (!isUrl(value)) return false;

  const url = value as string;
  return url.toLowerCase().includes(".pdf");
};
