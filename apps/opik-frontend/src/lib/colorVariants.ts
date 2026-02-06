import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

export function resolveColor(
  label: string,
  workspaceColorMap?: Record<string, string> | null,
  customColorMap?: Record<string, string> | null,
): string {
  if (workspaceColorMap?.[label]) return workspaceColorMap[label];
  if (customColorMap?.[label]) return customColorMap[label];
  return TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!];
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
