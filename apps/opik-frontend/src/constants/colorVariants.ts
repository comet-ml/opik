import md5 from "md5";

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

export const COLOR_VARIANTS_MAP: Record<ColorVariant, string> = {
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
};

export function getRandomColorByLabel(label: string): string {
  const hash = md5(label);
  const numericHash = parseInt(hash.slice(-8), 16);
  const index = numericHash % COLOR_VARIANTS.length;
  return COLOR_VARIANTS_MAP[COLOR_VARIANTS[index]];
}
