import React from "react";
import { z } from "zod";
import { ImageIcon } from "lucide-react";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
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
 * Uses the shared ImagesListWrapper component for consistent image rendering.
 *
 * Features:
 * - Preview image with hover-based expand/download actions
 * - Uses AttachmentThumbnail via ImagesListWrapper
 * - Optional tag at bottom
 *
 * Styles:
 * - Thumbnail: 200px height (AttachmentThumbnail standard)
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: bg-primary-foreground
 */
export const ImageWidget: React.FC<ImageWidgetProps> = ({
  src,
  alt,
  label,
  tag,
}) => {
  if (!src) return null;

  const mediaData = [
    {
      url: src,
      name: label || alt || "Image",
      type: ATTACHMENT_TYPE.IMAGE as const,
    },
  ];

  return (
    <div className="flex flex-col gap-1 py-0.5">
      {/* Image preview using shared component */}
      <ImagesListWrapper media={mediaData} />

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
    </div>
  );
};

export default ImageWidget;
