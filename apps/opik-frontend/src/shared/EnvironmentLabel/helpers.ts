import { DEFAULT_HEX_COLOR, HEX_COLOR_REGEX } from "@/constants/colorVariants";

export const resolveEnvironmentColor = (color: string | undefined) =>
  color && HEX_COLOR_REGEX.test(color) ? color : DEFAULT_HEX_COLOR;

export const getContrastingTextColor = (hex: string): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const luminance = (r * 299 + g * 587 + b * 114) / 1000;
  return luminance > 140 ? "#0f172a" : "#ffffff";
};
