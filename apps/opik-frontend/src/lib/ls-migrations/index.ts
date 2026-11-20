import { runMigrations } from "./engine";
import { migration001 } from "./001_fix_column_order";
import { migration002 } from "./002_split_experiments_tabs";

export function runLocalStorageMigrations(): void {
  runMigrations([migration001, migration002]);
}
