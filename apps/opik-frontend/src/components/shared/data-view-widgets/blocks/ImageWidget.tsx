import React, { useState } from "react";
import { z } from "zod";
import { Download, Expand, ImageIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AttachmentPreviewDialog from "@/components/pages-shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";
import { ATTACHMENT_TYPE } from "@/types/attachments";

// ============================================================================
// TYPES
// ============================================================================

export interface ImageWidgetProps {
  src: string;
  alt?: string | null;
  label?: string | null;
  tag?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const imageWidgetConfig = {
  type: "Image" as const,
  category: "block" as const,
  schema: z.object({
    src: DynamicString.describe("Image URL or data URI"),
    alt: NullableDynamicString.describe("Alt text for accessibility"),
    label: NullableDynamicString.describe("Label displayed above the image"),
    tag: NullableDynamicString.describe("Tag displayed below the image"),
  }),
  description:
    "Image display widget with preview, expand, and download capabilities.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * ImageWidget - Image display block
 *
 * Figma reference: Node 239-15694
 * Features:
 * - Preview image with border and rounded corners
 * - Label at top with expand/download icons
 * - Optional tag at bottom
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: rgba(248,250,252,0.25)
 * - Label: 12px Inter, #45575F (comet-body-xs)
 * - Tag: 14px Inter Medium, #45575F with #EBF2F5 background
 */
export const ImageWidget: React.FC<ImageWidgetProps> = ({
  src,
  alt,
  label,
  tag,
}) => {
  const [dialogOpen, setDialogOpen] = useState(false);

  if (!src) return null;

  const handleExpand = () => {
    setDialogOpen(true);
  };

  const handleDownload = () => {
    const link = document.createElement("a");
    link.href = src;
    link.download = alt || "image";
    link.click();
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
            {label || "Image"}
          </span>
          <TooltipWrapper content="Expand">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleExpand}
              aria-label="Expand image"
            >
              <Expand className="size-4" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Download">
            <Button
              variant="ghost"
              size="icon-2xs"
              onClick={handleDownload}
              aria-label="Download image"
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>

        {/* Image preview */}
        <div className="overflow-hidden rounded">
          <img
            src={src}
            alt={alt || "Image preview"}
            loading="lazy"
            className="h-40 w-full object-contain"
          />
        </div>
      </div>

      {/* Tag - clickable link to the image source */}
      {tag && (
        <div className="flex items-center gap-1.5">
          <a
            href={src}
            target="_blank"
            rel="noopener noreferrer"
            className="flex max-w-full items-center gap-1 rounded bg-[#ebf2f5] px-1.5 py-0.5 transition-colors hover:bg-[#dde8ed]"
          >
            <ImageIcon className="size-3 shrink-0 text-muted-slate" />
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
        type={ATTACHMENT_TYPE.IMAGE}
        name={label || alt || "Image"}
        url={src}
      />
    </div>
  );
};

export default ImageWidget;
