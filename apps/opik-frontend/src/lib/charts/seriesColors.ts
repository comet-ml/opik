import { labelHash } from "@/lib/labelHash";
import { ChartTokens, SERIES_COUNT, resolveCssColor } from "./chartTokens";

/**
 * Series colour assignment from the chart rules (src/styles/chart-tokens):
 *   1. A user override (the workspace colour map) wins.
 *   2. Every other key takes --chart-(hash(key) % 10 + 1), the same hash Opik's own chart colours use, so a key has
 *      the same colour for everyone and in every chart. A collision takes the next free slot.
 *   3. Keys past the ten palette slots take --chart-other.
 * Fills use the base colour, thin marks (lines, points, rules) the -strong variant.
 */

export interface AssignOptions {
  // Thin marks take the -strong variant.
  strong: boolean;
  override?: (key: string) => string | undefined;
}

export const assignSeriesColors = (
  keys: string[],
  tokens: ChartTokens,
  { strong, override }: AssignOptions,
): string[] => {
  const palette = strong ? tokens.paletteStrong : tokens.palette;
  const other = strong ? tokens.otherStrong : tokens.other;
  const result = new Map<string, string>();
  const used = new Set<number>();

  keys.forEach((key) => {
    const custom = override?.(key);
    if (custom) result.set(key, resolveCssColor(custom));
  });

  // Collisions resolve in label order, so the outcome depends on which keys are in the chart, not on row order.
  [...new Set(keys)]
    .filter((key) => !result.has(key))
    .sort()
    .forEach((key) => {
      const home = labelHash(key) % SERIES_COUNT;
      const slot = Array.from(
        { length: SERIES_COUNT },
        (_, step) => (home + step) % SERIES_COUNT,
      ).find((i) => !used.has(i));
      if (slot === undefined) {
        result.set(key, other);
        return;
      }
      used.add(slot);
      result.set(key, palette[slot]);
    });

  return keys.map((key) => result.get(key)!);
};
