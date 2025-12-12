import { useEffect, useState, useRef } from "react";
import isString from "lodash/isString";
import isEmpty from "lodash/isEmpty";
import {
  parseVideoValue,
  parseImageValue,
  parseAudioValue,
} from "@/lib/images";
import {
  detectMediaTypeFromUrl,
  isHttpUrl,
  createMediaData,
} from "@/lib/media";
import { ParsedMediaData, ATTACHMENT_TYPE } from "@/types/attachments";

export type DetectedMediaType = "video" | "image" | "audio" | "none";

export type UseMediaTypeDetectionReturn = {
  mediaType: DetectedMediaType;
  mediaData: ParsedMediaData | null;
  isDetecting: boolean;
};

const detectionCache = new Map<
  string,
  {
    mediaType: DetectedMediaType;
    mediaData: ParsedMediaData | null;
  }
>();

/**
 * Custom hook to detect media type from a string value using a 3-tier detection strategy.
 *
 * **3-Tier Detection Strategy:**
 * - **Tier 1**: Check URL string with regex to identify file extensions (fast path)
 * - **Tier 2**: Parse base64 data URIs to extract MIME types from their attributes
 * - **Tier 3**: Make HEAD request to URL and read Content-Type header (fallback for extensionless URLs)
 *
 * **Supported Media Types:**
 * - Video (e.g., .mp4, .webm, video/*)
 * - Image (e.g., .jpg, .png, image/*)
 * - Audio (e.g., .mp3, .wav, audio/*)
 *
 * **Hook Features:**
 * - Tries synchronous detection first (Tiers 1 & 2) for performance
 * - Falls back to async HEAD request (Tier 3) for extensionless URLs
 * - Caches results to avoid redundant detections
 * - Handles cleanup properly to prevent memory leaks
 *
 * @param value - The value to detect media type from. Pass null to skip detection.
 * @param enabled - Whether to enable detection. When false, returns empty state without processing. Default: true
 * @returns Object containing detected media type, parsed data, and detection state
 */
export const useMediaTypeDetection = (
  value: unknown,
  enabled: boolean = true,
): UseMediaTypeDetectionReturn => {
  const [mediaType, setMediaType] = useState<DetectedMediaType>("none");
  const [mediaData, setMediaData] = useState<ParsedMediaData | null>(null);
  const [isDetecting, setIsDetecting] = useState(false);

  const isCancelledRef = useRef(false);

  useEffect(() => {
    if (!enabled || !isString(value) || isEmpty(value)) {
      setMediaType("none");
      setMediaData(null);
      setIsDetecting(false);
      return;
    }

    const stringValue = value;

    const cached = detectionCache.get(stringValue);
    if (cached) {
      setMediaType(cached.mediaType);
      setMediaData(cached.mediaData);
      setIsDetecting(false);
      return;
    }

    isCancelledRef.current = false;

    const updateResult = (
      type: DetectedMediaType,
      data: ParsedMediaData | null,
    ) => {
      const result = { mediaType: type, mediaData: data };
      detectionCache.set(stringValue, result);
      setMediaType(result.mediaType);
      setMediaData(result.mediaData);
      setIsDetecting(false);
    };

    const syncVideo = parseVideoValue(stringValue);
    if (syncVideo) {
      const mediaData: ParsedMediaData = {
        ...syncVideo,
        type: ATTACHMENT_TYPE.VIDEO,
      };
      updateResult("video", mediaData);
      return;
    }

    const syncImage = parseImageValue(stringValue);
    if (syncImage) {
      const mediaData: ParsedMediaData = {
        ...syncImage,
        type: ATTACHMENT_TYPE.IMAGE,
      };
      updateResult("image", mediaData);
      return;
    }

    const syncAudio = parseAudioValue(stringValue);
    if (syncAudio) {
      const mediaData: ParsedMediaData = {
        ...syncAudio,
        type: ATTACHMENT_TYPE.AUDIO,
      };
      updateResult("audio", mediaData);
      return;
    }

    setIsDetecting(true);

    const detectAsync = async () => {
      try {
        if (!isHttpUrl(stringValue)) {
          if (!isCancelledRef.current) {
            updateResult("none", null);
          }
          return;
        }

        const mediaInfo = await detectMediaTypeFromUrl(stringValue);

        if (isCancelledRef.current) return;

        if (!mediaInfo) {
          updateResult("none", null);
          return;
        }

        const { category, mimeType } = mediaInfo;
        const mediaData = createMediaData(stringValue, category, mimeType);

        if (mediaData) {
          updateResult(category, mediaData);
        } else {
          updateResult("none", null);
        }
      } catch (error) {
        console.error("Error during async media detection:", error);
        if (!isCancelledRef.current) {
          updateResult("none", null);
        }
      }
    };

    detectAsync();

    return () => {
      isCancelledRef.current = true;
    };
  }, [value, enabled]);

  return { mediaType, mediaData, isDetecting };
};
