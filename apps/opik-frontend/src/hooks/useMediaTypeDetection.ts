import { useEffect, useMemo, useState, useRef } from "react";
import isString from "lodash/isString";
import isEmpty from "lodash/isEmpty";
import {
  parseVideoValue,
  parseImageValue,
  parseAudioValue,
  isVideoBase64String,
  isImageBase64String,
  isAudioBase64String,
} from "@/lib/images";
import {
  detectMediaTypeFromUrl,
  isHttpUrl,
  createMediaData,
} from "@/lib/media";
import {
  ParsedImageData,
  ParsedVideoData,
  ParsedAudioData,
  ParsedMediaData,
  ATTACHMENT_TYPE,
} from "@/types/attachments";

export type DetectedMediaType = "video" | "image" | "audio" | "none";

export type UseMediaTypeDetectionReturn = {
  mediaType: DetectedMediaType;
  mediaData: ParsedMediaData | null;
  isDetecting: boolean;
};

type DetectionResult = {
  mediaType: DetectedMediaType;
  mediaData: ParsedMediaData | null;
};

const detectionCache = new Map<string, DetectionResult>();

const NONE_RESULT: DetectionResult = { mediaType: "none", mediaData: null };

// Max string length to run URL regex against. Beyond this, only check
// data URI prefixes / known base64 signatures — never scan for http URLs.
const MAX_URL_REGEX_LENGTH = 10_000;

function cacheAndReturn(
  key: string,
  parsed: ParsedImageData | ParsedVideoData | ParsedAudioData | undefined,
  mediaType: DetectedMediaType,
  attachmentType: ATTACHMENT_TYPE,
): DetectionResult | null {
  if (!parsed) return null;
  const result: DetectionResult = {
    mediaType,
    mediaData: { ...parsed, type: attachmentType } as ParsedMediaData,
  };
  detectionCache.set(key, result);
  return result;
}

function detectSync(stringValue: string): DetectionResult | null {
  const cached = detectionCache.get(stringValue);
  if (cached) return cached;

  // Fast path for data URIs: only check matching type, skip URL regex
  if (stringValue.startsWith("data:")) {
    const result =
      (isVideoBase64String(stringValue) &&
        cacheAndReturn(
          stringValue,
          parseVideoValue(stringValue),
          "video",
          ATTACHMENT_TYPE.VIDEO,
        )) ||
      (isImageBase64String(stringValue) &&
        cacheAndReturn(
          stringValue,
          parseImageValue(stringValue),
          "image",
          ATTACHMENT_TYPE.IMAGE,
        )) ||
      (isAudioBase64String(stringValue) &&
        cacheAndReturn(
          stringValue,
          parseAudioValue(stringValue),
          "audio",
          ATTACHMENT_TYPE.AUDIO,
        )) ||
      null;

    if (result) return result;
    detectionCache.set(stringValue, NONE_RESULT);
    return NONE_RESULT;
  }

  // For very long strings, skip the URL regex scan entirely — they're
  // raw base64 or truncated data, not HTTP URLs.
  if (stringValue.length > MAX_URL_REGEX_LENGTH) {
    const result = cacheAndReturn(
      stringValue,
      parseImageValue(stringValue),
      "image",
      ATTACHMENT_TYPE.IMAGE,
    );
    if (result) return result;
    detectionCache.set(stringValue, NONE_RESULT);
    return NONE_RESULT;
  }

  // Normal-length strings: run full sync detection (extension + base64 checks)
  return (
    cacheAndReturn(
      stringValue,
      parseVideoValue(stringValue),
      "video",
      ATTACHMENT_TYPE.VIDEO,
    ) ??
    cacheAndReturn(
      stringValue,
      parseImageValue(stringValue),
      "image",
      ATTACHMENT_TYPE.IMAGE,
    ) ??
    cacheAndReturn(
      stringValue,
      parseAudioValue(stringValue),
      "audio",
      ATTACHMENT_TYPE.AUDIO,
    ) ??
    null
  );
}

/**
 * Custom hook to detect media type from a string value using a 3-tier detection strategy.
 *
 * **3-Tier Detection Strategy:**
 * - **Tier 1**: Check URL string with regex to identify file extensions (fast path)
 * - **Tier 2**: Parse base64 data URIs to extract MIME types from their attributes
 * - **Tier 3**: Make HEAD request to URL and read Content-Type header (fallback for extensionless URLs)
 *
 * Tiers 1 & 2 run synchronously in useMemo (available on first render).
 * Tier 3 runs asynchronously in useEffect (only for extensionless HTTP URLs).
 */
export const useMediaTypeDetection = (
  value: unknown,
  enabled: boolean = true,
): UseMediaTypeDetectionReturn => {
  const isCancelledRef = useRef(false);

  // Tiers 1 & 2: synchronous detection — available on first render
  const syncResult = useMemo((): {
    result: DetectionResult | null;
    needsAsync: boolean;
  } => {
    if (!enabled || !isString(value) || isEmpty(value)) {
      return { result: NONE_RESULT, needsAsync: false };
    }

    const detected = detectSync(value);
    if (detected) {
      return { result: detected, needsAsync: false };
    }

    return { result: null, needsAsync: isHttpUrl(value) };
  }, [value, enabled]);

  // Tier 3: async detection via HEAD request (only for extensionless HTTP URLs)
  const [asyncResult, setAsyncResult] = useState<DetectionResult | null>(null);
  const [isDetecting, setIsDetecting] = useState(false);

  useEffect(() => {
    if (!syncResult.needsAsync) {
      setAsyncResult(null);
      setIsDetecting(false);
      return;
    }

    if (!isString(value) || isEmpty(value)) return;

    const stringValue = value;
    isCancelledRef.current = false;
    setIsDetecting(true);

    const detectAsync = async () => {
      try {
        const mediaInfo = await detectMediaTypeFromUrl(stringValue);

        if (isCancelledRef.current) return;

        if (!mediaInfo) {
          const result = NONE_RESULT;
          detectionCache.set(stringValue, result);
          setAsyncResult(result);
          setIsDetecting(false);
          return;
        }

        const { category, mimeType } = mediaInfo;
        const data = createMediaData(stringValue, category, mimeType);
        const result: DetectionResult = data
          ? { mediaType: category, mediaData: data }
          : NONE_RESULT;

        detectionCache.set(stringValue, result);
        setAsyncResult(result);
        setIsDetecting(false);
      } catch (error) {
        console.error("Error during async media detection:", error);
        if (!isCancelledRef.current) {
          detectionCache.set(stringValue, NONE_RESULT);
          setAsyncResult(NONE_RESULT);
          setIsDetecting(false);
        }
      }
    };

    detectAsync();

    return () => {
      isCancelledRef.current = true;
    };
  }, [value, syncResult.needsAsync]);

  // Sync result takes priority; fallback to async result
  if (syncResult.result) {
    return {
      mediaType: syncResult.result.mediaType,
      mediaData: syncResult.result.mediaData,
      isDetecting: false,
    };
  }

  if (asyncResult) {
    return {
      mediaType: asyncResult.mediaType,
      mediaData: asyncResult.mediaData,
      isDetecting: false,
    };
  }

  return { mediaType: "none", mediaData: null, isDetecting };
};
