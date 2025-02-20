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
  gray: "#64748B",
  purple: "#945FCF",
  burgundy: "#BF399E",
  pink: "#ED4A7B",
  red: "#EF6868",
  orange: "#FB9341",
  yellow: "#F4B400",
  green: "#19A979",
  turquoise: "#12A4B4",
  blue: "#5899DA",
};

export function getRandomColorByLabel(label: string): string {
  const hash = md5(label);
  const numericHash = parseInt(hash.slice(-8), 16);
  const index = numericHash % COLOR_VARIANTS.length;
  return COLOR_VARIANTS_MAP[COLOR_VARIANTS[index]];
}
