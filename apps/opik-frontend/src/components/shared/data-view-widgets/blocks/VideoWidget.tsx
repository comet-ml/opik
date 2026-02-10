import React from "react";
import { z } from "zod";
import { CirclePlay } from "lucide-react";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { ATTACHMENT_TYPE } from "@/types/attachments";

// ============================================================================
// TYPES
// ============================================================================

export interface VideoWidgetProps {
  src: string;
  label?: string | null;
  tag?: string | null;
  controls?: boolean;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const videoWidgetConfig = {
  type: "Video" as const,
  category: "block" as const,
  schema: z.object({
    src: DynamicString.describe("Video URL"),
    label: NullableDynamicString.describe("Label displayed above the video"),
    tag: NullableDynamicString.describe("Tag displayed below the video"),
    controls: NullableDynamicBoolean.describe(
      "Show native video controls (default: true)",
    ),
  }),
  description:
    "Video player widget with preview thumbnail and playback controls.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * VideoWidget - Video player block
 *
 * Figma reference: Node 239-15694 (Video section)
 * Uses the shared ImagesListWrapper component for consistent video rendering.
 *
 * Features:
 * - Preview thumbnail with hover-based expand/download actions
 * - Uses AttachmentThumbnail via ImagesListWrapper
 * - Optional tag at bottom
 *
 * Styles:
 * - Thumbnail: 200px height (AttachmentThumbnail standard)
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: bg-primary-foreground
 */
export const VideoWidget: React.FC<VideoWidgetProps> = ({
  src,
  label,
  tag,
}) => {
  if (!src) return null;

  const mediaData = [
    {
      url: src,
      name: label || "Video",
      type: ATTACHMENT_TYPE.VIDEO as const,
    },
  ];

  return (
    <div className="flex flex-col gap-1 py-0.5">
      {/* Video preview using shared component */}
      <ImagesListWrapper media={mediaData} />

      {/* Tag - clickable link to the video source */}
      {tag && (
        <div className="flex items-center gap-1.5">
          <a
            href={src}
            target="_blank"
            rel="noopener noreferrer"
            className="flex max-w-full items-center gap-1 rounded bg-[#ebf2f5] px-1.5 py-0.5 transition-colors hover:bg-[#dde8ed]"
          >
            <CirclePlay className="size-3 shrink-0 text-muted-slate" />
            <span className="comet-body-s-accented truncate text-muted-slate">
              {tag}
            </span>
          </a>
        </div>
      )}
    </div>
  );
};

export default VideoWidget;
