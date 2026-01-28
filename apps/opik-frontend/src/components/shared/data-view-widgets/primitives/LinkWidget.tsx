import React from "react";
import { z } from "zod";
import { SquareArrowOutUpRight } from "lucide-react";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";

// ============================================================================
// SECURITY
// ============================================================================

const ALLOWED_PROTOCOLS = ["http:", "https:", "mailto:"];

/**
 * Validates a URL to prevent XSS attacks via javascript:, data:, vbscript: protocols.
 * Only allows http:, https:, and mailto: protocols.
 */
function isValidUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    return ALLOWED_PROTOCOLS.includes(parsed.protocol);
  } catch {
    // If URL parsing fails, reject the URL
    return false;
  }
}

// ============================================================================
// TYPES
// ============================================================================

export interface LinkWidgetProps {
  url: string;
  text?: string | null;
  label?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const linkWidgetConfig = {
  type: "Link" as const,
  category: "inline" as const,
  schema: z.object({
    url: DynamicString.describe("Target URL"),
    text: NullableDynamicString.describe("Display text (defaults to URL)"),
    label: NullableDynamicString.describe("Optional label prefix"),
  }),
  description: "External URL hyperlink with icon.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * LinkWidget - External URL hyperlink
 *
 * Figma reference: Node 239-15133
 * Style:
 * - 14px Regular Inter, #5155F5 (primary blue)
 * - height: 24px
 * - gap: 4px between text and icon
 * - icon: square-arrow-out-up-right (14x14)
 */
export const LinkWidget: React.FC<LinkWidgetProps> = ({ url, text, label }) => {
  if (!url) return null;

  // Security: Reject potentially malicious URLs (javascript:, data:, vbscript:, etc.)
  if (!isValidUrl(url)) {
    return (
      <span className="inline-flex items-center gap-2">
        {label && (
          <span className="comet-body-s text-muted-slate">{label}</span>
        )}
        <span className="comet-body-s text-muted-slate">[Invalid URL]</span>
      </span>
    );
  }

  const displayText = text ?? url;

  return (
    <span className="inline-flex items-center gap-2">
      {label && <span className="comet-body-s text-muted-slate">{label}</span>}
      <a
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex h-6 items-center gap-1 text-primary hover:text-primary/80"
      >
        <span className="comet-body-s">{displayText}</span>
        <SquareArrowOutUpRight className="size-3.5" />
      </a>
    </span>
  );
};

export default LinkWidget;
