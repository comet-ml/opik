import { Code, FlaskRound, LucideIcon, Rocket } from "lucide-react";
import { DEFAULT_HEX_COLOR, HEX_COLOR_REGEX } from "@/constants/colorVariants";
import {
  DEFAULT_ENVIRONMENT_NAMES,
  DefaultEnvironmentName,
} from "@/utils/environments";

export const resolveEnvironmentColor = (color: string | undefined) =>
  color && HEX_COLOR_REGEX.test(color) ? color : DEFAULT_HEX_COLOR;

export const getContrastingTextColor = (hex: string): string => {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const luminance = (r * 299 + g * 587 + b * 114) / 1000;
  return luminance > 140 ? "#0f172a" : "#ffffff";
};

// Seeded server-side by migration 000066_seed_default_environments. The set of
// names is the source of truth in `utils/environments.ts`
// (DEFAULT_ENVIRONMENT_NAMES); TypeScript enforces this map carries one entry
// per default env so adding a new default forces editing both color/icon and
// sort priority together.
type DefaultEnvironmentMeta = { color: string; icon: LucideIcon };

const DEFAULT_ENVIRONMENTS: Record<
  DefaultEnvironmentName,
  DefaultEnvironmentMeta
> = {
  production: { color: "#19A979", icon: Rocket },
  staging: { color: "#F4B400", icon: FlaskRound },
  development: { color: "#EF6868", icon: Code },
};

const normalize = (name: string | undefined | null): string =>
  name?.trim().toLowerCase() ?? "";

const isDefaultName = (name: string): name is DefaultEnvironmentName =>
  (DEFAULT_ENVIRONMENT_NAMES as readonly string[]).includes(name);

export const getDefaultEnvironmentMeta = (
  name: string | undefined | null,
): DefaultEnvironmentMeta | null => {
  const normalized = normalize(name);
  return isDefaultName(normalized) ? DEFAULT_ENVIRONMENTS[normalized] : null;
};

export const isDefaultEnvironmentName = (name: string | undefined | null) =>
  isDefaultName(normalize(name));
