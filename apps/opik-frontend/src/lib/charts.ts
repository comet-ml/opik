import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

export const getDefaultHashedColorsChartConfig = (
  lines: string[],
  labelsMap?: Record<string, string>,
  predefinedColorMap: Record<string, string> = {},
) => {
  return lines.reduce<ChartConfig>((acc, line) => {
    acc[line] = {
      label: labelsMap?.[line] ?? line,
      color:
        predefinedColorMap[line] ||
        TAG_VARIANTS_COLOR_MAP[generateTagVariant(line)!],
    };
    return acc;
  }, {});
};

export const hexToRgba = (hex: string, opacity: number = 1): string => {
  let cleanHex = hex.replace(/^#/, "");

  let alpha = opacity;
  if (cleanHex.length === 8) {
    alpha = parseInt(cleanHex.slice(6, 8), 16) / 255;
    cleanHex = cleanHex.slice(0, 6);
  }

  if (cleanHex.length === 3) {
    cleanHex = cleanHex
      .split("")
      .map((char) => char + char)
      .join("");
  }

  const r = parseInt(cleanHex.slice(0, 2), 16);
  const g = parseInt(cleanHex.slice(2, 4), 16);
  const b = parseInt(cleanHex.slice(4, 6), 16);

  if (isNaN(r) || isNaN(g) || isNaN(b)) {
    throw new Error(`Invalid hex color: ${hex}`);
  }

  alpha = Math.max(0, Math.min(1, alpha));

  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};
