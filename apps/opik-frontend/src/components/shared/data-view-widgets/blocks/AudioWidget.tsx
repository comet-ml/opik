import React from "react";
import { z } from "zod";
import { Music } from "lucide-react";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import AudioPlayer from "@/components/shared/AudioPlayer/AudioPlayer";

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
      "Show audio controls (default: true)",
    ),
  }),
  description:
    "Audio player widget with custom styled controls and progress bar.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * AudioWidget - Audio player block
 *
 * Figma reference: Node 239-15695
 * Uses the shared AudioPlayer component for consistent audio playback UI.
 *
 * Features:
 * - Custom styled audio controls with play/pause/seek
 * - Progress bar visualization
 * - Optional tag at bottom
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: bg-primary-foreground
 * - Label: 12px Inter, #45575F
 */
export const AudioWidget: React.FC<AudioWidgetProps> = ({
  src,
  label,
  tag,
}) => {
  if (!src) return null;

  return (
    <div className="flex w-full flex-col gap-1 py-0.5">
      {/* Audio player using shared component */}
      <AudioPlayer url={src} name={label || "Audio"} />

      {/* Tag - clickable link to the audio source */}
      {tag && (
        <div className="flex items-center gap-1.5">
          <a
            href={src}
            target="_blank"
            rel="noopener noreferrer"
            className="flex max-w-full items-center gap-1 rounded bg-[#ebf2f5] px-1.5 py-0.5 transition-colors hover:bg-[#dde8ed]"
          >
            <Music className="size-3 shrink-0 text-muted-slate" />
            <span className="comet-body-s-accented truncate text-muted-slate">
              {tag}
            </span>
          </a>
        </div>
      )}
    </div>
  );
};

export default AudioWidget;
