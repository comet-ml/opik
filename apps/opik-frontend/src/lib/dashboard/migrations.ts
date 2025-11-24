import { DashboardState } from "@/types/dashboard";
import { generateEmptyDashboard } from "@/lib/dashboard/utils";
import isArray from "lodash/isArray";
import isNumber from "lodash/isNumber";
import isObject from "lodash/isObject";

export const DASHBOARD_VERSION = 1;

export const migrateDashboardV1toV2 = (
  dashboard: DashboardState,
): DashboardState => {
  return {
    ...dashboard,
    version: 2,
  };
};

export const migrateDashboardConfig = (config: unknown): DashboardState => {
  if (!isObject(config)) {
    return generateEmptyDashboard();
  }

  const dashboard = config as Partial<DashboardState>;

  if (!dashboard.version) {
    return {
      sections: dashboard.sections || [],
      version: 1,
      lastModified: dashboard.lastModified || Date.now(),
    };
  }

  return applyDashboardMigrations(dashboard);
};

export const applyDashboardMigrations = (
  dashboard: unknown,
): DashboardState => {
  if (!isObject(dashboard)) {
    return {
      sections: [],
      version: DASHBOARD_VERSION,
      lastModified: Date.now(),
    };
  }

  const migratedDashboard = { ...dashboard } as DashboardState;

  if (!migratedDashboard.version || migratedDashboard.version < 1) {
    migratedDashboard.version = 1;
  }

  if (!isArray(migratedDashboard.sections)) {
    migratedDashboard.sections = [];
  }

  if (!isNumber(migratedDashboard.lastModified)) {
    migratedDashboard.lastModified = Date.now();
  }

  return migratedDashboard;
};

export const isDashboardStateValid = (
  data: unknown,
): data is DashboardState => {
  if (!isObject(data)) return false;

  const dashboard = data as Partial<DashboardState>;

  return (
    isNumber(dashboard.version) &&
    isArray(dashboard.sections) &&
    isNumber(dashboard.lastModified)
  );
};
