export interface Migration {
  id: string;
  description: string;
  run: () => void;
}

const TRACKING_KEY = "opik-ls-migrations";

export function runMigrations(migrations: Migration[]): void {
  let completed: string[] = [];
  try {
    const raw = localStorage.getItem(TRACKING_KEY);
    if (raw) completed = JSON.parse(raw);
    if (!Array.isArray(completed)) completed = [];
  } catch {
    completed = [];
  }

  for (const migration of migrations) {
    if (completed.includes(migration.id)) continue;
    try {
      migration.run();
      completed.push(migration.id);
      localStorage.setItem(TRACKING_KEY, JSON.stringify(completed));
    } catch (e) {
      console.error(`[ls-migrations] "${migration.id}" failed:`, e);
    }
  }
}
