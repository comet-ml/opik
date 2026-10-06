import { ChartTokens, SERIES_COUNT, resolveCssColor } from "./chartTokens";

/**
 * Series colour assignment from the chart rules (src/styles/widget-tokens, docs/chart-rules.md):
 *   1. A user override (the workspace colour map) wins.
 *   2. A key of a known entity kind (model, tool, project…) keeps the palette slot it got the first time it was
 *      seen, in every widget; each kind has its own sequence from --chart-1.
 *   3. Other keys take the next --chart-1…10 not already used in this chart.
 *   4. From the 11th colour on: generated colours, seeded by the key so a reload gives the same colour.
 *   5. Keys beyond MAX_SERIES take --chart-other.
 * Fills use the base colour, thin marks (lines, points, rules) the -strong variant.
 */

export const MAX_SERIES = 20;

const ENTITY_KINDS = [
  "model",
  "experiment",
  "project",
  "tool",
  "agent",
  "environment",
  "spantype",
  "cohort",
  "locale",
  "genre",
  "plantier",
  "device",
  "usertenure",
  "geography",
  "language",
];

// The registry lives in the browser until the workspace colour map can store entity slots server-side.
const REGISTRY_KEY = "opik-chart-entity-colors";

type Registry = Record<string, Record<string, number>>;

const readRegistry = (): Registry => {
  try {
    return JSON.parse(localStorage.getItem(REGISTRY_KEY) ?? "{}") as Registry;
  } catch {
    return {};
  }
};

const writeRegistry = (registry: Registry) => {
  try {
    localStorage.setItem(REGISTRY_KEY, JSON.stringify(registry));
  } catch {
    // Storage can be blocked; slots then hold for this chart only.
  }
};

// "model", "model_name", "spanType", "plan_tier" → the entity kind, if the field names one.
export const entityKind = (field: string): string | undefined => {
  const normalized = field.toLowerCase().replace(/[^a-z]/g, "");
  return ENTITY_KINDS.find(
    (kind) =>
      normalized === kind ||
      normalized === `${kind}name` ||
      normalized === `${kind}s`,
  );
};

const hash = (key: string) => {
  let value = 0;
  for (let i = 0; i < key.length; i += 1) {
    value = (value * 31 + key.charCodeAt(i)) | 0;
  }
  return Math.abs(value);
};

// Seeded by the key; dark enough for ~3:1 on white and stepped away from the palette's hues.
const generatedColor = (key: string, avoidHues: number[]): string => {
  let hue = hash(key) % 360;
  for (let step = 0; step < 24; step += 1) {
    const clash = avoidHues.some(
      (h) => Math.min(Math.abs(h - hue), 360 - Math.abs(h - hue)) < 14,
    );
    if (!clash) break;
    hue = (hue + 17) % 360;
  }
  return `hsl(${hue} 55% 42%)`;
};

const hueOf = (color: string): number | undefined => {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color);
  if (!match) return undefined;
  const [r, g, b] = match.slice(1).map((c) => parseInt(c, 16) / 255);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  if (max === min) return undefined;
  const d = max - min;
  const h =
    max === r
      ? ((g - b) / d) % 6
      : max === g
        ? (b - r) / d + 2
        : (r - g) / d + 4;
  return (h * 60 + 360) % 360;
};

export interface AssignOptions {
  field: string;
  // Thin marks take the -strong variant.
  strong: boolean;
  override?: (key: string) => string | undefined;
}

export const assignSeriesColors = (
  keys: string[],
  tokens: ChartTokens,
  { field, strong, override }: AssignOptions,
): string[] => {
  const palette = strong ? tokens.paletteStrong : tokens.palette;
  const other = strong ? tokens.otherStrong : tokens.other;
  const kind = entityKind(field);
  const registry = kind ? readRegistry() : {};
  const slots = kind ? (registry[kind] ??= {}) : {};
  const used = new Set<number>();
  const result: (string | undefined)[] = new Array(keys.length);

  // Overrides and remembered entity slots first, so palette order fills around them.
  keys.forEach((key, index) => {
    if (index >= MAX_SERIES) return;
    const custom = override?.(key);
    if (custom) {
      result[index] = resolveCssColor(custom);
      return;
    }
    const slot = slots[key];
    if (slot !== undefined && slot < SERIES_COUNT && !used.has(slot)) {
      used.add(slot);
      result[index] = palette[slot];
    }
  });

  let registryChanged = false;
  const avoidHues = palette
    .map(hueOf)
    .filter((h): h is number => h !== undefined);
  const nextFree = () =>
    Array.from({ length: SERIES_COUNT }, (_, i) => i).find((i) => !used.has(i));

  keys.forEach((key, index) => {
    if (result[index]) return;
    if (index >= MAX_SERIES) {
      result[index] = other;
      return;
    }

    let slot: number | undefined;
    if (kind && slots[key] === undefined) {
      // A new entity takes its kind's next slot in sequence, and keeps it from then on.
      slots[key] = Object.keys(slots).length;
      registryChanged = true;
      if (slots[key] < SERIES_COUNT && !used.has(slots[key])) slot = slots[key];
    }
    slot ??= nextFree();

    if (slot === undefined) {
      result[index] = generatedColor(key, avoidHues);
      return;
    }
    used.add(slot);
    result[index] = palette[slot];
  });

  if (kind && registryChanged) writeRegistry(registry);
  return result as string[];
};
