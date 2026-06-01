import { Code, FlaskRound, LucideIcon, Rocket } from "lucide-react";
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

// Seeded server-side by migration 000066_seed_default_environments. Name +
// color are kept in lock-step with the backend so the UI can recognize them
// and protect their color from edits.
type DefaultEnvironmentMeta = { color: string; icon: LucideIcon };

const DEFAULT_ENVIRONMENTS: Record<string, DefaultEnvironmentMeta> = {
  development: { color: "#EF6868", icon: Code },
  staging: { color: "#F4B400", icon: FlaskRound },
  production: { color: "#19A979", icon: Rocket },
};

const normalize = (name: string | undefined | null) =>
  name?.trim().toLowerCase() ?? "";

export const getDefaultEnvironmentMeta = (
  name: string | undefined | null,
): DefaultEnvironmentMeta | null =>
  DEFAULT_ENVIRONMENTS[normalize(name)] ?? null;

export const isDefaultEnvironmentName = (name: string | undefined | null) =>
  normalize(name) in DEFAULT_ENVIRONMENTS;
