import React, { useRef, useState } from "react";
import { z } from "zod";
import { CirclePlay, Download, Expand, Play } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AttachmentPreviewDialog from "@/components/pages-shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";
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
 * Features:
 * - Preview thumbnail with play button overlay
 * - Label at top with expand/download icons
 * - Optional tag at bottom
 * - Native video controls on play
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: rgba(248,250,252,0.25)
 * - Play button: white bg, border, centered
 */
export const VideoWidget: React.FC<VideoWidgetProps> = ({
  src,
  label,
  tag,
  controls = true,
}) => {
  const [isPlaying, setIsPlaying] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  if (!src) return null;

  const handleExpand = () => {
    setDialogOpen(true);
  };

  const handleDownload = () => {
    const link = document.createElement("a");
    link.href = src;
    link.download = label || "video";
    link.click();
  };

  const handlePlay = () => {
    setIsPlaying(true);
    setTimeout(() => {
      videoRef.current?.play();
    }, 100);
  };

  return (
    <div className="flex flex-col gap-1 py-0.5">
      {/* Preview container */}
      <div
        className={cn(
          "flex flex-col gap-2 overflow-hidden rounded-md border border-border bg-slate-50/25 p-2",
        )}
      >
        {/* Header with label and actions */}
        <div className="flex h-4 items-center gap-2.5">
          <span className="comet-body-xs flex-1 truncate text-muted-slate">
            {label || "Video"}
          </span>
          <TooltipWrapper content="Expand">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleExpand}
              aria-label="Expand video"
            >
              <Expand className="size-4" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Download">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleDownload}
              aria-label="Download video"
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>

        {/* Video preview / player */}
        <div className="relative overflow-hidden rounded">
          {isPlaying ? (
            <video
              ref={videoRef}
              src={src}
              controls={controls}
              className="h-40 w-full object-contain"
            >
              Your browser does not support video playback.
            </video>
          ) : (
            <div
              className="relative h-40 w-full cursor-pointer bg-slate-100"
              onClick={handlePlay}
            >
              {/* Thumbnail - use video poster or first frame */}
              <video
                src={src}
                preload="metadata"
                className="size-full object-contain"
                muted
              />
              {/* Play button overlay */}
              <div className="absolute inset-0 flex items-center justify-center">
                <Button
                  variant="outline"
                  size="icon"
                  className="size-8 rounded-md bg-white"
                  aria-label="Play video"
                >
                  <Play className="size-3.5" />
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>

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

      {/* Fullscreen preview dialog */}
      <AttachmentPreviewDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        type={ATTACHMENT_TYPE.VIDEO}
        name={label || "Video"}
        url={src}
      />
    </div>
  );
};

export default VideoWidget;
