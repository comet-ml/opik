import { ParsedMediaData, ATTACHMENT_TYPE } from "@/types/attachments";
import uniqBy from "lodash/uniqBy";

export type MediaCategory = "video" | "image" | "audio";

const extractFilename = (url: string): string => {
  const match = url.match(/[^/\\?#]+(?=[?#"]|$)/);
  return match ? match[0] : url;
};

export type MediaTypeInfo = {
  category: MediaCategory;
  mimeType: string | null;
};

const HEAD_REQUEST_TIMEOUT_MS = 5000;

const mediaTypeCache = new Map<string, MediaTypeInfo | null>();

const ALL_URL_REGEX = /https?:\/\/[^\s"'<>{}\\|^`]+/gi;

export const isHttpUrl = (value: string): boolean => {
  try {
    const urlObj = new URL(value);
    return urlObj.protocol === "http:" || urlObj.protocol === "https:";
  } catch {
    return false;
  }
};

export const detectMediaTypeFromUrl = async (
  url: string,
): Promise<MediaTypeInfo | null> => {
  if (mediaTypeCache.has(url)) {
    return mediaTypeCache.get(url) || null;
  }

  let response: Response | null = null;

  try {
    // Try HEAD request first (most efficient)
    response = await fetch(url, {
      method: "HEAD",
      signal: AbortSignal.timeout(HEAD_REQUEST_TIMEOUT_MS),
    });

    // Only fallback to GET for specific cases (e.g., 405 Method Not Allowed)
    // Don't retry for client/server errors (4xx, 5xx) to avoid security issues
    if (!response.ok) {
      // Check if it's an HTTP error that shouldn't be retried
      if (response.status >= 400) {
        // Cache failure for 4xx and 5xx errors (403 Forbidden, 404 Not Found, 500, etc.)
        mediaTypeCache.set(url, null);
        return null;
      }

      // Only retry for other cases like 405 Method Not Allowed
      response = await fetch(url, {
        method: "GET",
        headers: {
          Range: "bytes=0-0", // Request only first byte to minimize data transfer
        },
        signal: AbortSignal.timeout(HEAD_REQUEST_TIMEOUT_MS),
      });
    }
  } catch (headError) {
    // HEAD request failed (CORS, network error, timeout, etc.)
    // Fallback to GET with Range header for network-level failures only
    try {
      response = await fetch(url, {
        method: "GET",
        headers: {
          Range: "bytes=0-0",
        },
        signal: AbortSignal.timeout(HEAD_REQUEST_TIMEOUT_MS),
      });
    } catch (getError) {
      mediaTypeCache.set(url, null);
      return null;
    }
  }

  try {
    if (!response || !response.ok) {
      mediaTypeCache.set(url, null);
      return null;
    }

    const contentType = response.headers.get("Content-Type");

    if (!contentType) {
      mediaTypeCache.set(url, null);
      return null;
    }

    const lowerType = contentType.toLowerCase();
    let result: MediaTypeInfo | null = null;

    if (lowerType.startsWith("video/")) {
      result = { category: "video", mimeType: contentType };
    } else if (lowerType.startsWith("image/")) {
      result = { category: "image", mimeType: contentType };
    } else if (lowerType.startsWith("audio/")) {
      result = { category: "audio", mimeType: contentType };
    }

    mediaTypeCache.set(url, result);
    return result;
  } catch (error) {
    mediaTypeCache.set(url, null);
    return null;
  }
};

/**
 * Converts media type information to ParsedMediaData structure.
 * Shared helper to avoid duplication between hook and utility functions.
 *
 * @param url - The media URL
 * @param category - The detected media category (video, image, audio)
 * @param mimeType - Optional MIME type from Content-Type header
 * @returns ParsedMediaData object or null if category is invalid
 */
export const createMediaData = (
  url: string,
  category: MediaCategory,
  mimeType: string | null,
): ParsedMediaData | null => {
  const filename = extractFilename(url);

  if (category === "video") {
    return {
      url,
      name: filename || "Video",
      type: ATTACHMENT_TYPE.VIDEO,
      ...(mimeType && { mimeType }),
    };
  }

  if (category === "image") {
    return {
      url,
      name: filename || "Image",
      type: ATTACHMENT_TYPE.IMAGE,
    };
  }

  if (category === "audio") {
    return {
      url,
      name: filename || "Audio",
      type: ATTACHMENT_TYPE.AUDIO,
      ...(mimeType && { mimeType }),
    };
  }

  return null;
};

export const detectMediaFromUrl = async (
  url: string,
): Promise<ParsedMediaData | null> => {
  if (!isHttpUrl(url)) {
    return null;
  }

  const mediaInfo = await detectMediaTypeFromUrl(url);
  if (!mediaInfo) {
    return null;
  }

  const { category, mimeType } = mediaInfo;
  return createMediaData(url, category, mimeType);
};

export const clearMediaTypeCache = (): void => {
  mediaTypeCache.clear();
};

export const getMediaTypeCacheSize = (): number => {
  return mediaTypeCache.size;
};

export const extractAllHttpUrls = (input: string): string[] => {
  const matches = input.match(ALL_URL_REGEX) || [];
  const cleanedUrls = matches.map((url) => url.replace(/[.,;:!?)}\]]+$/, ""));
  return [...new Set(cleanedUrls)];
};

export const detectAdditionalMedia = async (
  input: object | undefined,
  existingMedia: ParsedMediaData[],
): Promise<ParsedMediaData[]> => {
  if (!input) {
    return existingMedia;
  }

  const inputString = JSON.stringify(input);
  const allUrlsInInput = extractAllHttpUrls(inputString);
  const detectedUrls = new Set(existingMedia.map((media) => media.url));
  const undetectedUrls = allUrlsInInput.filter((url) => !detectedUrls.has(url));

  const detectionResults = await Promise.all(
    undetectedUrls.map((url) => detectMediaFromUrl(url)),
  );

  const newMedia = detectionResults.filter(
    (mediaData): mediaData is ParsedMediaData => mediaData !== null,
  );

  return uniqBy([...existingMedia, ...newMedia], "url");
};
