import React from "react";
import { VideoIcon } from "lucide-react";
import { useInView } from "react-intersection-observer";

import { useVideoThumbnail } from "@/hooks/useVideoThumbnail";

export type VideoThumbnailProps = {
  /** URL of the video to generate thumbnail from */
  videoUrl: string;
  /** Name of the video file for accessibility */
  name: string;
};

type ThumbnailContainerProps = {
  children: React.ReactNode;
};

type ThumbnailImageProps = {
  url: string;
  alt: string;
};

const STYLES = {
  container:
    "flex size-full items-center justify-center rounded-sm bg-primary-foreground",
  image: "size-full object-contain",
  icon: "size-8 text-slate-300",
  loadingSkeleton: "size-8 animate-pulse rounded bg-slate-300",
} as const;

const INTERSECTION_OPTIONS = {
  rootMargin: "100px",
  threshold: 0.01,
  triggerOnce: true,
};

const VIDEO_ICON_STROKE_WIDTH = 1.33;

/**
 * Container wrapper for video thumbnail states
 * Provides consistent styling across loading, error, and success states
 */
const ThumbnailContainer = React.forwardRef<
  HTMLDivElement,
  ThumbnailContainerProps
>(({ children }, ref) => (
  <div ref={ref} className={STYLES.container}>
    {children}
  </div>
));
ThumbnailContainer.displayName = "ThumbnailContainer";

/**
 * Displays the generated video thumbnail image
 */
const ThumbnailImage: React.FC<ThumbnailImageProps> = ({ url, alt }) => (
  <img src={url} alt={alt} className={STYLES.image} loading="lazy" />
);

/**
 * Loading state indicator
 * Shows animated skeleton while thumbnail is being generated
 */
const LoadingState = React.forwardRef<HTMLDivElement>((_, ref) => (
  <ThumbnailContainer ref={ref}>
    <div
      className={STYLES.loadingSkeleton}
      role="status"
      aria-label="Loading video thumbnail"
    />
  </ThumbnailContainer>
));
LoadingState.displayName = "LoadingState";

/**
 * Error/fallback state indicator
 * Shows video icon when thumbnail generation fails
 */
const ErrorState = React.forwardRef<HTMLDivElement>((_, ref) => (
  <ThumbnailContainer ref={ref}>
    <VideoIcon
      className={STYLES.icon}
      strokeWidth={VIDEO_ICON_STROKE_WIDTH}
      aria-label="Video thumbnail unavailable"
    />
  </ThumbnailContainer>
));
ErrorState.displayName = "ErrorState";

/**
 * Determines if thumbnail generation has failed
 */
const hasThumbnailFailed = (
  hasError: boolean,
  isLoading: boolean,
  thumbnailUrl: string | null,
): boolean => {
  return hasError || (!isLoading && !thumbnailUrl);
};

/**
 * VideoThumbnail component
 *
 * Generates and displays a thumbnail for a video file with lazy loading support.
 * Uses intersection observer to only generate thumbnails when visible in viewport.
 *
 * @example
 * ```tsx
 * <VideoThumbnail
 *   videoUrl="https://example.com/video.mp4"
 *   name="My Video"
 * />
 * ```
 */
const VideoThumbnail: React.FC<VideoThumbnailProps> = ({ videoUrl, name }) => {
  // Only generate thumbnail when component is visible in viewport
  const { ref: containerRef, inView: isVisible } =
    useInView(INTERSECTION_OPTIONS);

  // Generate thumbnail from video
  const { thumbnailUrl, isLoading, hasError } = useVideoThumbnail(
    videoUrl,
    {},
    isVisible,
  );

  // Render error state
  if (hasThumbnailFailed(hasError, isLoading, thumbnailUrl)) {
    return <ErrorState ref={containerRef} />;
  }

  // Render loading state
  if (isLoading) {
    return <LoadingState ref={containerRef} />;
  }

  // Render success state with thumbnail
  return <ThumbnailImage url={thumbnailUrl!} alt={name} />;
};

export default VideoThumbnail;
