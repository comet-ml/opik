import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { CSS_VAR_TO_HEX, HEX_COLOR_REGEX } from "@/constants/colorVariants";

export function resolveHexColor(color: string): string {
  if (HEX_COLOR_REGEX.test(color)) return color;
  return CSS_VAR_TO_HEX[color] ?? color;
}

export function resolveColor(
  colorKey: string,
  workspaceColorMap?: Record<string, string> | null,
  customColorMap?: Record<string, string> | null,
): string {
  if (workspaceColorMap?.[colorKey]) return workspaceColorMap[colorKey];
  if (customColorMap?.[colorKey]) return customColorMap[colorKey];
  return TAG_VARIANTS_COLOR_MAP[generateTagVariant(colorKey)!];
}

export function resolveChartColorMap(
  labels: string[],
  workspaceColorMap?: Record<string, string> | null,
  customColorMap?: Record<string, string> | null,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const label of labels) {
    result[label] = resolveColor(label, workspaceColorMap, customColorMap);
  }
  return result;
}
