import md5 from "md5";

export const HEX_COLOR_REGEX = /^#[0-9a-fA-F]{6}$/;

export const COLOR_VARIANTS = [
  "gray",
  "purple",
  "burgundy",
  "pink",
  "red",
  "orange",
  "yellow",
  "green",
  "turquoise",
  "blue",
] as const;

export type ColorVariant = (typeof COLOR_VARIANTS)[number];
export type ExtendedColorVariant = ColorVariant | "primary";

export const COLOR_VARIANTS_MAP: Record<ExtendedColorVariant, string> = {
  gray: "var(--color-gray)",
  purple: "var(--color-purple)",
  burgundy: "var(--color-burgundy)",
  pink: "var(--color-pink)",
  red: "var(--color-red)",
  orange: "var(--color-orange)",
  yellow: "var(--color-yellow)",
  green: "var(--color-green)",
  turquoise: "var(--color-turquoise)",
  blue: "var(--color-blue)",
  primary: "var(--color-primary)",
};

export const COLOR_VARIANTS_HEX_MAP: Record<ExtendedColorVariant, string> = {
  gray: "#64748b",
  purple: "#8b5cf6",
  burgundy: "#bf399e",
  pink: "#f43f5e",
  red: "#ef4444",
  orange: "#f97316",
  yellow: "#eab308",
  green: "#10b981",
  turquoise: "#06b6d4",
  blue: "#3b82f6",
  primary: "#6366f1",
};

export const PRESET_HEX_COLORS = COLOR_VARIANTS.map(
  (v) => COLOR_VARIANTS_HEX_MAP[v],
);

export const DEFAULT_HEX_COLOR = COLOR_VARIANTS_HEX_MAP.blue;

const CSS_VAR_TO_HEX: Record<string, string> = Object.fromEntries(
  [...COLOR_VARIANTS, "primary" as const].map((v) => [
    COLOR_VARIANTS_MAP[v],
    COLOR_VARIANTS_HEX_MAP[v],
  ]),
);

export function resolveHexColor(color: string): string {
  if (HEX_COLOR_REGEX.test(color)) return color;
  return CSS_VAR_TO_HEX[color] ?? color;
}

export function getRandomColorByLabel(label: string): string {
  const hash = md5(label);
  const numericHash = parseInt(hash.slice(-8), 16);
  const index = numericHash % COLOR_VARIANTS.length;
  return COLOR_VARIANTS_MAP[COLOR_VARIANTS[index]];
}
