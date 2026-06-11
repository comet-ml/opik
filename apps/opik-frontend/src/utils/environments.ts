// Canonical list of default environment names seeded by backend migration
// 000066. The order here drives the sort priority below AND the icon/color
// lookup in shared/EnvironmentLabel/helpers.ts — keep both in lock-step by
// editing this list, not its consumers.
export const DEFAULT_ENVIRONMENT_NAMES = [
  "production",
  "staging",
  "development",
] as const;

export type DefaultEnvironmentName = (typeof DEFAULT_ENVIRONMENT_NAMES)[number];

const DEFAULT_ENV_SORT_PRIORITY: Record<string, number> = Object.fromEntries(
  DEFAULT_ENVIRONMENT_NAMES.map((name, index) => [name, index]),
);

export const sortEnvironments = <T extends { name: string }>(envs: T[]): T[] =>
  envs.slice().sort((a, b) => {
    const aKey = a.name.trim().toLowerCase();
    const bKey = b.name.trim().toLowerCase();
    const aPriority = DEFAULT_ENV_SORT_PRIORITY[aKey] ?? Infinity;
    const bPriority = DEFAULT_ENV_SORT_PRIORITY[bKey] ?? Infinity;
    if (aPriority !== bPriority) return aPriority - bPriority;
    return a.name.localeCompare(b.name);
  });
