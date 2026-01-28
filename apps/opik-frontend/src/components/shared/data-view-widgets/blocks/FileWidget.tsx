import React from "react";
import { z } from "zod";
import { Download, ExternalLink, File } from "lucide-react";
import { cn } from "@/lib/utils";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

// ============================================================================
// TYPES
// ============================================================================

export interface FileWidgetProps {
  url: string;
  filename?: string | null;
  label?: string | null;
  type?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const fileWidgetConfig = {
  type: "File" as const,
  category: "block" as const,
  schema: z.object({
    url: DynamicString.describe("File URL"),
    filename: NullableDynamicString.describe(
      "Display filename (defaults to URL basename)",
    ),
    label: NullableDynamicString.describe("Label above the file info"),
    type: NullableDynamicString.describe("File type/extension hint"),
  }),
  description: "File download widget with filename display and download link.",
};

// ============================================================================
// HELPERS
// ============================================================================

function getFilenameFromUrl(url: string): string {
  try {
    const pathname = new URL(url).pathname;
    const basename = pathname.split("/").pop();
    return basename || "file";
  } catch {
    return "file";
  }
}

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * FileWidget - File download block
 *
 * Figma reference: Node 239-10067
 * Features:
 * - File icon + filename display
 * - Download link
 * - Optional label
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: rgba(248,250,252,0.25)
 * - Icon: File icon, slate-400
 */
export const FileWidget: React.FC<FileWidgetProps> = ({
  url,
  filename,
  label,
  type,
}) => {
  if (!url) return null;

  const displayFilename = filename || getFilenameFromUrl(url);

  const handleDownload = () => {
    const link = document.createElement("a");
    link.href = url;
    link.download = displayFilename;
    link.click();
  };

  const handleOpen = () => {
    window.open(url, "_blank");
  };

  return (
    <div className="flex flex-col gap-1 py-0.5">
      {/* Label */}
      {label && (
        <span className="comet-body-xs px-2 text-muted-slate">{label}</span>
      )}

      {/* File container */}
      <div
        className={cn(
          "flex items-center gap-3 overflow-hidden rounded-md border border-border bg-slate-50/25 p-3",
        )}
      >
        {/* File icon */}
        <div className="flex size-10 shrink-0 items-center justify-center rounded bg-slate-100">
          <File className="size-5 text-slate-400" strokeWidth={1.5} />
        </div>

        {/* File info */}
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="comet-body-s truncate font-medium text-foreground">
            {displayFilename}
          </span>
          {type && (
            <span className="comet-body-xs text-muted-slate">
              {type.toUpperCase()}
            </span>
          )}
        </div>

        {/* Actions */}
        <div className="flex shrink-0 items-center gap-1">
          <TooltipWrapper content="Open in new tab">
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={handleOpen}
              aria-label="Open file"
            >
              <ExternalLink className="size-4" />
            </Button>
          </TooltipWrapper>
          <TooltipWrapper content="Download">
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={handleDownload}
              aria-label="Download file"
            >
              <Download className="size-4" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>
    </div>
  );
};

export default FileWidget;
