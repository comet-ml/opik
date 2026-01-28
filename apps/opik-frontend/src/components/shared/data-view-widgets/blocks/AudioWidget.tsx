import React, { useState } from "react";
import { z } from "zod";
import { Download, Expand, Music } from "lucide-react";
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

export interface AudioWidgetProps {
  src: string;
  label?: string | null;
  tag?: string | null;
  controls?: boolean;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const audioWidgetConfig = {
  type: "Audio" as const,
  category: "block" as const,
  schema: z.object({
    src: DynamicString.describe("Audio URL"),
    label: NullableDynamicString.describe("Label displayed above the player"),
    tag: NullableDynamicString.describe("Tag displayed below the player"),
    controls: NullableDynamicBoolean.describe(
      "Show native audio controls (default: true)",
    ),
  }),
  description: "Audio player widget with native browser controls.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * AudioWidget - Audio player block
 *
 * Figma reference: Node 239-15695
 * Features:
 * - Native audio controls with play/pause/seek
 * - Label at top with expand/download icons
 * - Optional tag at bottom
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: rgba(248,250,252,0.25)
 * - Label: 12px Inter, #45575F
 */
export const AudioWidget: React.FC<AudioWidgetProps> = ({
  src,
  label,
  tag,
  controls = true,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);

  if (!src) return null;

  const handleExpand = () => {
    setDialogOpen(true);
  };

  const handleDownload = () => {
    const link = document.createElement("a");
    link.href = src;
    link.download = label || "audio";
    link.click();
  };

  return (
    <div className="flex flex-col gap-1 py-0.5">
      {/* Preview container */}
      <div
        className={cn(
          "flex flex-col gap-1 overflow-hidden rounded-md border border-border bg-slate-50/25 p-2",
        )}
      >
        {/* Header with label and actions */}
        <div className="flex h-4 items-center gap-2.5">
          <span className="comet-body-xs flex-1 truncate text-muted-slate">
            {label || "Audio"}
          </span>
          <TooltipWrapper content="Expand">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleExpand}
              aria-label="Expand audio"
            >
              <Expand className="size-4" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Download">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleDownload}
              aria-label="Download audio"
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>

        {/* Audio player */}
        <div className="flex items-center gap-2">
          <audio src={src} controls={controls} className="h-8 w-full">
            Your browser does not support audio playback.
          </audio>
        </div>
      </div>

      {/* Tag */}
      {tag && (
        <div className="flex items-center gap-1.5">
          <div className="flex items-center gap-1 rounded bg-[#ebf2f5] px-1.5 py-0.5">
            <Music className="size-3 text-muted-slate" />
            <span className="comet-body-s-accented text-muted-slate">
              {tag}
            </span>
          </div>
        </div>
      )}

      {/* Fullscreen preview dialog */}
      <AttachmentPreviewDialog
        open={dialogOpen}
        setOpen={setDialogOpen}
        type={ATTACHMENT_TYPE.AUDIO}
        name={label || "Audio"}
        url={src}
      />
    </div>
  );
};

export default AudioWidget;
