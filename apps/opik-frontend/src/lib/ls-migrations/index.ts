import { runMigrations } from "./engine";
import { migration001 } from "./001_fix_column_order";

export function runLocalStorageMigrations(): void {
  runMigrations([migration001]);
}
