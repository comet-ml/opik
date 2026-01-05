import AnsiToHtml from "ansi-to-html";

/**
 * Terminal output utilities.
 * Handles terminal output conversion to HTML with proper color theming,
 * terminal control sequence cleanup, and OSC 8 hyperlink support.
 */

// ANSI to HTML converter with colors that work on both light and dark backgrounds
// Uses medium-saturation colors that maintain contrast in either mode
export const ansiConverter = new AnsiToHtml({
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
export const cleanTerminalControls = (text: string): string => {
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
 * Information about an extracted hyperlink
 */
export type LinkInfo = { url: string; text: string };

/**
 * Extract OSC 8 hyperlinks and replace with placeholders before ANSI processing.
 * Rich uses OSC 8 format: \x1b]8;params;URL\x1b\\TEXT\x1b]8;;\x1b\\
 * or with BEL terminator: \x1b]8;params;URL\x07TEXT\x1b]8;;\x07
 */
export const extractOsc8Links = (
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
export const restoreOsc8Links = (
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

/**
 * Convert terminal output to HTML with proper handling of:
 * - Terminal control sequences
 * - OSC 8 hyperlinks
 * - ANSI color codes
 */
export const convertTerminalOutputToHtml = (text: string): string => {
  if (!text) return "";

  // 1. Clean up terminal control sequences that ansi-to-html doesn't handle
  const cleaned = cleanTerminalControls(text);
  // 2. Extract OSC 8 hyperlinks and replace with placeholders
  const { processed, links } = extractOsc8Links(cleaned);
  // 3. Convert ANSI color codes to HTML
  const withColors = ansiConverter.toHtml(processed);
  // 4. Restore hyperlinks from placeholders
  return restoreOsc8Links(withColors, links);
};
