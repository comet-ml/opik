const DEFAULT_ENV_SORT_PRIORITY: Record<string, number> = {
  production: 0,
  staging: 1,
  development: 2,
};

export const sortEnvironments = <T extends { name: string }>(envs: T[]): T[] =>
  envs.slice().sort((a, b) => {
    const aKey = a.name.trim().toLowerCase();
    const bKey = b.name.trim().toLowerCase();
    const aPriority = DEFAULT_ENV_SORT_PRIORITY[aKey] ?? Infinity;
    const bPriority = DEFAULT_ENV_SORT_PRIORITY[bKey] ?? Infinity;
    if (aPriority !== bPriority) return aPriority - bPriority;
    return a.name.localeCompare(b.name);
  });
