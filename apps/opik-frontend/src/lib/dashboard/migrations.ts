import {
  DashboardState,
  WIDGET_TYPE,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import {
  generateEmptyDashboard,
  DASHBOARD_VERSION,
} from "@/lib/dashboard/utils";
import isObject from "lodash/isObject";
import isEmpty from "lodash/isEmpty";

type MigrationFunction = (dashboard: DashboardState) => DashboardState;

const migrateDashboardV1toV2 = (dashboard: DashboardState): DashboardState => {
  return {
    ...dashboard,
    version: 2,
    sections: dashboard.sections.map((section) => ({
      ...section,
      widgets: section.widgets.map((widget) => {
        if (
          widget.type === WIDGET_TYPE.PROJECT_METRICS ||
          widget.type === WIDGET_TYPE.PROJECT_STATS_CARD
        ) {
          const hasCustomProject = !isEmpty(widget.config.projectId);
          return {
            ...widget,
            config: {
              ...widget.config,
              overrideDefaults: hasCustomProject,
            },
          };
        }

        if (widget.type === WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES) {
          const hasCustomConfig =
            !isEmpty(widget.config.experimentIds) ||
            widget.config.dataSource !==
              EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

          return {
            ...widget,
            config: {
              ...widget.config,
              overrideDefaults: hasCustomConfig,
            },
          };
        }

        return widget;
      }),
    })),
  };
};

const migrateDashboardV2toV3 = (dashboard: DashboardState): DashboardState => {
  return {
    ...dashboard,
    version: 3,
    config: {
      ...dashboard.config,
      experimentDataSource:
        dashboard.config.experimentDataSource ||
        EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
      experimentFilters: dashboard.config.experimentFilters || [],
      maxExperimentsCount: dashboard.config.maxExperimentsCount || 10,
    },
    sections: dashboard.sections.map((section) => ({
      ...section,
      widgets: section.widgets.map((widget) => {
        if (widget.type === WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES) {
          return {
            ...widget,
            config: {
              ...widget.config,
              maxExperimentsCount:
                widget.config.maxExperimentsCount !== undefined
                  ? widget.config.maxExperimentsCount
                  : 10,
            },
          };
        }

        return widget;
      }),
    })),
  };
};

const migrations: Record<number, MigrationFunction> = {
  2: migrateDashboardV1toV2,
  3: migrateDashboardV2toV3,
};

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
