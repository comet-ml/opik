import React, { useMemo } from "react";
import { RotateCw } from "lucide-react";
import AnsiToHtml from "ansi-to-html";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Optimization } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";

// ANSI to HTML converter with colors that work on both light and dark backgrounds
// Uses medium-saturation colors that maintain contrast in either mode
const ansiConverter = new AnsiToHtml({
  fg: "#374151", // gray-700 - readable on both light and dark
  bg: "transparent",
  newline: true,
  escapeXML: true,
  colors: {
    // Standard colors (0-7) - using medium tones for universal readability
    0: "#1f2937", // black -> gray-800
    1: "#dc2626", // red -> red-600
    2: "#16a34a", // green -> green-600
    3: "#ca8a04", // yellow -> yellow-600
    4: "#2563eb", // blue -> blue-600
    5: "#9333ea", // magenta -> purple-600
    6: "#0891b2", // cyan -> cyan-600
    7: "#6b7280", // white -> gray-500
    // Bright colors (8-15)
    8: "#4b5563", // bright black -> gray-600
    9: "#ef4444", // bright red -> red-500
    10: "#22c55e", // bright green -> green-500
    11: "#eab308", // bright yellow -> yellow-500
    12: "#3b82f6", // bright blue -> blue-500
    13: "#a855f7", // bright magenta -> purple-500
    14: "#06b6d4", // bright cyan -> cyan-500
    15: "#9ca3af", // bright white -> gray-400
  },
});

/**
 * Clean up terminal control sequences that ansi-to-html doesn't handle.
 * These include cursor controls, terminal modes, and other non-color escapes.
 * Also handles cases where ESC character may have been stripped, leaving just [...]
 */
/* eslint-disable no-control-regex */
const cleanTerminalControls = (text: string): string => {
  return (
    text
      // Remove cursor show/hide: ESC[?25h (show) and ESC[?25l (hide)
      // Also handle case where ESC is missing
      .replace(/\x1b\[\?25[hl]/g, "")
      .replace(/\[\?25[hl]/g, "")
      // Remove other DEC private modes: ESC[?...h or ESC[?...l
      .replace(/\x1b\[\?[0-9;]*[hl]/g, "")
      .replace(/\[\?[0-9;]*[hl]/g, "")
      // Remove cursor position saves/restores: ESC[s, ESC[u, ESC7, ESC8
      .replace(/\x1b\[?[su78]/g, "")
      // Remove erase commands: ESC[K (erase line), ESC[J (erase screen), etc.
      .replace(/\x1b\[[0-9]*[JK]/g, "")
      // Remove cursor movement: ESC[nA (up), ESC[nB (down), ESC[nC (forward), ESC[nD (back)
      .replace(/\x1b\[[0-9]*[ABCD]/g, "")
      // Remove cursor position: ESC[n;mH or ESC[n;mf
      .replace(/\x1b\[[0-9;]*[Hf]/g, "")
      // Remove scroll region: ESC[n;mr
      .replace(/\x1b\[[0-9;]*r/g, "")
      // Remove orphaned SGR codes without ESC prefix (e.g., [2m, [0m, [1;32m)
      // These appear when ESC was stripped by some processing step
      // Only match codes that are preceded by start of string, newline, or whitespace
      // and only match realistic SGR codes (1-2 digits per segment)
      .replace(/(^|[\n\s])\[([0-9]{1,2}(;[0-9]{1,3})*)m/g, "$1")
      // Remove lines that are ONLY whitespace or control chars after cleaning
      .split("\n")
      .filter((line) => line.trim().length > 0)
      .join("\n")
  );
};

/**
 * Extract OSC 8 hyperlinks and replace with placeholders before ANSI processing.
 * Rich uses OSC 8 format: \x1b]8;params;URL\x1b\\TEXT\x1b]8;;\x1b\\
 * or with BEL terminator: \x1b]8;params;URL\x07TEXT\x1b]8;;\x07
 */
type LinkInfo = { url: string; text: string };

const extractOsc8Links = (
  text: string,
): { processed: string; links: Map<string, LinkInfo> } => {
  const links = new Map<string, LinkInfo>();
  let counter = 0;

  // Match OSC 8 hyperlinks with either ST (\x1b\\) or BEL (\x07) terminators
  const osc8Regex =
    /\x1b\]8;([^;]*);([^\x07\x1b]*?)(?:\x07|\x1b\\)([\s\S]*?)\x1b\]8;;(?:\x07|\x1b\\)/g;

  const processed = text.replace(
    osc8Regex,
    (_match, _params, url, linkText) => {
      const placeholder = `__OPIK_LINK_${counter++}__`;
      links.set(placeholder, { url, text: linkText });
      return placeholder;
    },
  );

  return { processed, links };
};
/* eslint-enable no-control-regex */

/**
 * Restore OSC 8 links from placeholders after ANSI processing.
 */
const restoreOsc8Links = (
  html: string,
  links: Map<string, LinkInfo>,
): string => {
  let result = html;

  links.forEach(({ url, text }, placeholder) => {
    // Escape URL for href attribute
    const safeUrl = url.replace(/&/g, "&amp;").replace(/"/g, "&quot;");

    // Text was already escaped by ansi-to-html, use as-is
    const safeText = text
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");

    const link = `<a href="${safeUrl}" target="_blank" rel="noopener noreferrer" class="text-blue-400 underline hover:text-blue-300">${safeText}</a>`;
    result = result.replace(placeholder, link);
  });

  return result;
};

type OptimizationLogsProps = {
  optimization: Optimization | null;
};

const OptimizationLogs: React.FC<OptimizationLogsProps> = ({
  optimization,
}) => {
  const { data, isPending, refetch } = useOptimizationStudioLogs(
    {
      optimizationId: optimization?.id ?? "",
    },
    {
      enabled: Boolean(optimization?.id),
      refetchInterval:
        optimization?.status &&
        IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization?.status)
          ? OPTIMIZATION_ACTIVE_REFETCH_INTERVAL
          : undefined,
      retry: false,
    },
  );

  const logContent = data?.content ?? "";

  // Convert ANSI escape codes to HTML for Rich-formatted logs
  const logHtml = useMemo(() => {
    if (!logContent) return "";
    // 1. Clean up terminal control sequences that ansi-to-html doesn't handle
    const cleaned = cleanTerminalControls(logContent);
    // 2. Extract OSC 8 hyperlinks and replace with placeholders
    const { processed, links } = extractOsc8Links(cleaned);
    // 3. Convert ANSI color codes to HTML
    const withColors = ansiConverter.toHtml(processed);
    // 4. Restore hyperlinks from placeholders
    return restoreOsc8Links(withColors, links);
  }, [logContent]);

  if (!optimization) {
    return null;
  }

  const renderContent = () => {
    if (isPending && !logContent) {
      return <Loader />;
    }

    if (!logContent) {
      return (
        <div className="flex flex-1 items-center justify-center">
          <div className="comet-body-s text-muted-slate">No logs available</div>
        </div>
      );
    }

    return (
      <div className="flex flex-1 flex-col overflow-hidden">
        <div className="flex-1 overflow-auto rounded-sm border border-border bg-muted/50 p-3">
          <pre
            className="whitespace-pre-wrap break-words font-mono text-xs leading-relaxed"
            dangerouslySetInnerHTML={{ __html: logHtml }}
          />
        </div>
      </div>
    );
  };

  return (
    <Card className="size-full">
      <CardContent className="flex h-full flex-col p-4">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="comet-body-s-accented">Logs</h3>
          <TooltipWrapper content="Refresh logs">
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => refetch()}
              disabled={isPending}
            >
              <RotateCw
                className={cn("size-3.5", isPending && "animate-spin")}
              />
            </Button>
          </TooltipWrapper>
        </div>

        {renderContent()}
      </CardContent>
    </Card>
  );
};

export default OptimizationLogs;
