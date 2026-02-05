import React from "react";
import { z } from "zod";
import { SquareArrowOutUpRight } from "lucide-react";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import { Button } from "@/components/ui/button";

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
export const LinkWidget: React.FC<LinkWidgetProps> = ({ url, text }) => {
  if (!url) return null;

  // Security: Reject potentially malicious URLs (javascript:, data:, vbscript:, etc.)
  if (!isValidUrl(url)) {
    return (
      <span className="comet-body-s text-muted-slate">[Invalid URL]</span>
    );
  }

  const displayText = text ?? url;

  return (
    <Button variant="link" size="2xs" asChild className="w-fit">
      <a href={url} target="_blank" rel="noopener noreferrer">
        {displayText}
        <SquareArrowOutUpRight className="ml-1 size-3.5 shrink-0" />
      </a>
    </Button>
  );
};

export default LinkWidget;
