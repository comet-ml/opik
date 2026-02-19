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
export type ExtendedColorVariant = ColorVariant | "primary" | "default";

export const COLOR_VARIANTS_MAP: Record<
  ExtendedColorVariant,
  { css: string; hex: string }
> = {
  gray: { css: "var(--color-gray)", hex: "#64748b" },
  purple: { css: "var(--color-purple)", hex: "#8b5cf6" },
  burgundy: { css: "var(--color-burgundy)", hex: "#bf399e" },
  pink: { css: "var(--color-pink)", hex: "#f43f5e" },
  red: { css: "var(--color-red)", hex: "#ef4444" },
  orange: { css: "var(--color-orange)", hex: "#f97316" },
  yellow: { css: "var(--color-yellow)", hex: "#eab308" },
  green: { css: "var(--color-green)", hex: "#10b981" },
  turquoise: { css: "var(--color-turquoise)", hex: "#06b6d4" },
  blue: { css: "var(--color-blue)", hex: "#3b82f6" },
  primary: { css: "var(--color-primary)", hex: "#6366f1" },
  default: { css: "var(--color-gray)", hex: "#64748b" },
};

export const PRESET_HEX_COLORS = COLOR_VARIANTS.map(
  (v) => COLOR_VARIANTS_MAP[v].hex,
);

export const DEFAULT_HEX_COLOR = COLOR_VARIANTS_MAP.blue.hex;

export const CSS_VAR_TO_HEX: Record<string, string> = Object.fromEntries(
  [...COLOR_VARIANTS, "primary" as const].map((v) => [
    COLOR_VARIANTS_MAP[v].css,
    COLOR_VARIANTS_MAP[v].hex,
  ]),
);
