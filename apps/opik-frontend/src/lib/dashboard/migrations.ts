import { DashboardState } from "@/types/dashboard";
import {
  generateEmptyDashboard,
  DASHBOARD_VERSION,
} from "@/lib/dashboard/utils";
import isObject from "lodash/isObject";

type MigrationFunction = (dashboard: DashboardState) => DashboardState;

const migrations: Record<number, MigrationFunction> = {};

export const migrateDashboardConfig = (
  config: DashboardState,
): DashboardState => {
  if (!isObject(config)) {
    console.error("Dashboard config is not an object");
    return generateEmptyDashboard();
  }

  const currentVersion = config.version || 0;
  let current = { ...config };

  for (
    let version = currentVersion + 1;
    version <= DASHBOARD_VERSION;
    version++
  ) {
    const migrationFn = migrations[version];
    if (migrationFn) {
      current = migrationFn(current);
    }
  }

  return current;
};

// Future migrations:
// Add migration functions here and register them in the migrations map
//
// Example for migrating to version 2:
//
// const migrateDashboardV1toV2 = (dashboard: DashboardState): DashboardState => {
//   return {
//     ...dashboard,
//     version: 2,
//     // Add any data transformations needed for v2
//   };
// };
//
// migrations[2] = migrateDashboardV1toV2;
