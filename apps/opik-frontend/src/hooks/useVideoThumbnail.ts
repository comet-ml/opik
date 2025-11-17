import { useEffect, useState, useRef } from "react";

export type UseVideoThumbnailReturn = {
  thumbnailUrl: string | null;
  isLoading: boolean;
  hasError: boolean;
};

type ThumbnailConfig = {
  quality: number;
  format: string;
  maxDimension: number;
  seekTimeSeconds: number;
  timeoutMs: number;
};

const DEFAULT_CONFIG: ThumbnailConfig = {
  quality: 0.8,
  format: "image/jpeg",
  maxDimension: 500,
  seekTimeSeconds: 0.1,
  timeoutMs: 10000,
};

// Global cache to store generated thumbnails
const thumbnailCache = new Map<string, string>();

export const useVideoThumbnail = (
  videoUrl: string,
  config: Partial<ThumbnailConfig> = {},
  shouldLoad: boolean = true,
): UseVideoThumbnailReturn => {
  const [thumbnailUrl, setThumbnailUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [hasError, setHasError] = useState(false);

  const isCancelledRef = useRef(false);
  const timeoutIdRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    // Validate video URL
    if (!videoUrl || typeof videoUrl !== "string" || !videoUrl.trim()) {
      setHasError(true);
      setIsLoading(false);
      setThumbnailUrl(null);
      return;
    }

    // Check cache first (always check cache regardless of shouldLoad)
    const cachedUrl = thumbnailCache.get(videoUrl);
    if (cachedUrl) {
      setThumbnailUrl(cachedUrl);
      setIsLoading(false);
      setHasError(false);
      return;
    }

    // Don't start loading if shouldLoad is false
    if (!shouldLoad) {
      setIsLoading(true);
      return;
    }

    // Reset state for new video
    isCancelledRef.current = false;
    setIsLoading(true);
    setHasError(false);

    // Merge config with defaults
    const finalConfig = { ...DEFAULT_CONFIG, ...config };

    // Create video and canvas elements
    const video = document.createElement("video");
    const canvas = document.createElement("canvas");

    video.preload = "metadata";
    video.muted = true;
    video.playsInline = true;

    // Set CORS if needed
    try {
      const urlObj = new URL(videoUrl, window.location.href);
      if (urlObj.origin !== window.location.origin) {
        video.crossOrigin = "anonymous";
      }
    } catch {
      // Invalid URL, will be caught by error handler
    }

    const cleanup = () => {
      isCancelledRef.current = true;

      if (timeoutIdRef.current) {
        clearTimeout(timeoutIdRef.current);
        timeoutIdRef.current = null;
      }

      video.removeEventListener("loadedmetadata", onLoadedMetadata);
      video.removeEventListener("seeked", onSeeked);
      video.removeEventListener("error", onError);

      try {
        video.pause();
        video.src = "";
        video.load();
      } catch {
        // Ignore cleanup errors
      }
    };

    const onLoadedMetadata = () => {
      if (isCancelledRef.current) return;

      try {
        const duration = video.duration;
        if (!isFinite(duration) || duration <= 0) {
          throw new Error("Invalid video duration");
        }

        // Seek to a frame near the beginning
        const seekTime = Math.min(finalConfig.seekTimeSeconds, duration * 0.1);
        video.currentTime = seekTime;
      } catch (error) {
        console.error("Error seeking video:", error);
        if (!isCancelledRef.current) {
          setHasError(true);
          setIsLoading(false);
        }
        cleanup();
      }
    };

    const onSeeked = () => {
      if (isCancelledRef.current) return;

      try {
        const videoWidth = video.videoWidth || 0;
        const videoHeight = video.videoHeight || 0;

        if (videoWidth < 1 || videoHeight < 1) {
          throw new Error("Invalid video dimensions");
        }

        // Calculate canvas dimensions maintaining aspect ratio
        let width = videoWidth;
        let height = videoHeight;

        if (
          width > finalConfig.maxDimension ||
          height > finalConfig.maxDimension
        ) {
          const aspectRatio = width / height;
          if (width > height) {
            width = finalConfig.maxDimension;
            height = Math.round(width / aspectRatio);
          } else {
            height = finalConfig.maxDimension;
            width = Math.round(height * aspectRatio);
          }
        }

        canvas.width = width;
        canvas.height = height;

        const ctx = canvas.getContext("2d", { alpha: false });
        if (!ctx) {
          throw new Error("Failed to get canvas context");
        }

        ctx.drawImage(video, 0, 0, width, height);

        canvas.toBlob(
          (blob) => {
            if (isCancelledRef.current || !blob || blob.size === 0) {
              cleanup();
              return;
            }

            try {
              const blobUrl = URL.createObjectURL(blob);

              // Cache the result
              thumbnailCache.set(videoUrl, blobUrl);

              setThumbnailUrl(blobUrl);
              setIsLoading(false);
              setHasError(false);
            } catch (error) {
              console.error("Error creating blob URL:", error);
              setHasError(true);
              setIsLoading(false);
            }

            cleanup();
          },
          finalConfig.format,
          finalConfig.quality,
        );
      } catch (error) {
        console.error("Error generating thumbnail:", error);
        if (!isCancelledRef.current) {
          setHasError(true);
          setIsLoading(false);
        }
        cleanup();
      }
    };

    const onError = (event: Event) => {
      if (isCancelledRef.current) return;

      const target = event.target as HTMLVideoElement;
      console.error("Error loading video:", {
        code: target?.error?.code,
        message: target?.error?.message,
        url: videoUrl,
      });

      setHasError(true);
      setIsLoading(false);
      cleanup();
    };

    const onTimeout = () => {
      if (!isCancelledRef.current) {
        console.error("Video thumbnail generation timed out");
        setHasError(true);
        setIsLoading(false);
        cleanup();
      }
    };

    // Set timeout
    timeoutIdRef.current = setTimeout(onTimeout, finalConfig.timeoutMs);

    // Add event listeners
    video.addEventListener("loadedmetadata", onLoadedMetadata);
    video.addEventListener("seeked", onSeeked);
    video.addEventListener("error", onError);

    // Start loading
    try {
      video.src = videoUrl;
      video.load();
    } catch (error) {
      console.error("Failed to load video:", error);
      setHasError(true);
      setIsLoading(false);
      cleanup();
    }

    return () => {
      cleanup();
    };
  }, [videoUrl, config, shouldLoad]);

  return { thumbnailUrl, isLoading, hasError };
};
