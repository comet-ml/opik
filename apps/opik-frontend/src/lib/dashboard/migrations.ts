import { DashboardState, WIDGET_TYPE } from "@/types/dashboard";
import {
  generateEmptyDashboard,
  DASHBOARD_VERSION,
} from "@/lib/dashboard/utils";
import isObject from "lodash/isObject";
import isEmpty from "lodash/isEmpty";

enum LEGACY_EXPERIMENT_DATA_SOURCE {
  FILTER_AND_GROUP = "filter_and_group",
  SELECT_EXPERIMENTS = "select_experiments",
}

// Intermediate type for migrations dealing with pre-v4 dashboards that had a global `config`
type LegacyDashboard = DashboardState & {
  config?: Record<string, unknown>;
};

type MigrationFunction = (dashboard: LegacyDashboard) => LegacyDashboard;

const migrateDashboardV1toV2 = (
  dashboard: LegacyDashboard,
): LegacyDashboard => {
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
              LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

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

const migrateDashboardV2toV3 = (
  dashboard: LegacyDashboard,
): LegacyDashboard => {
  const globalConfig = dashboard.config || {};
  return {
    ...dashboard,
    version: 3,
    config: {
      ...globalConfig,
      experimentDataSource:
        globalConfig.experimentDataSource ||
        LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
      experimentFilters: globalConfig.experimentFilters || [],
      maxExperimentsCount: globalConfig.maxExperimentsCount || 10,
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

const migrateDashboardV3toV4 = (
  dashboard: LegacyDashboard,
): LegacyDashboard => {
  const globalConfig = dashboard.config || {};

  const migrateExperimentWidgetConfig = (
    config: Record<string, unknown>,
  ): Record<string, unknown> => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { dataSource, experimentIds, overrideDefaults, ...rest } = config;

    const ids = Array.isArray(experimentIds) ? experimentIds : [];
    const validIds = ids.filter(
      (id) => typeof id === "string" && id.length > 0,
    );
    const isSelectMode =
      dataSource === LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

    if (isSelectMode && validIds.length > 0) {
      return {
        ...rest,
        filters: [
          {
            id: "experiment-ids-filter",
            field: "experiment_ids",
            type: "string",
            operator: "=",
            key: "",
            value: validIds.join(","),
          },
        ],
        groups: [],
      };
    }

    return rest;
  };

  const sections = dashboard.sections.map((section) => ({
    ...section,
    widgets: section.widgets.map((widget) => {
      const { overrideDefaults, ...restConfig } = widget.config as Record<
        string,
        unknown
      >;

      if (!overrideDefaults) {
        if (
          widget.type === WIDGET_TYPE.PROJECT_METRICS ||
          widget.type === WIDGET_TYPE.PROJECT_STATS_CARD
        ) {
          return {
            ...widget,
            config: {
              ...restConfig,
              projectId:
                (globalConfig?.projectIds as string[] | undefined)?.[0] || "",
            },
          };
        }
        if (widget.type === WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES) {
          const mergedConfig = {
            ...restConfig,
            dataSource:
              globalConfig?.experimentDataSource ||
              LEGACY_EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
            filters: globalConfig?.experimentFilters || [],
            maxExperimentsCount: globalConfig?.maxExperimentsCount ?? 10,
            experimentIds: globalConfig?.experimentIds || [],
          };
          return {
            ...widget,
            config: migrateExperimentWidgetConfig(mergedConfig),
          };
        }
        if (widget.type === WIDGET_TYPE.EXPERIMENT_LEADERBOARD) {
          const mergedConfig = {
            ...restConfig,
            dataSource:
              globalConfig?.experimentDataSource ||
              LEGACY_EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
            filters: globalConfig?.experimentFilters || [],
            maxRows: globalConfig?.maxExperimentsCount ?? 10,
            experimentIds: globalConfig?.experimentIds || [],
          };
          return {
            ...widget,
            config: migrateExperimentWidgetConfig(mergedConfig),
          };
        }
      }

      if (
        widget.type === WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES ||
        widget.type === WIDGET_TYPE.EXPERIMENT_LEADERBOARD
      ) {
        return {
          ...widget,
          config: migrateExperimentWidgetConfig(restConfig),
        };
      }

      return { ...widget, config: restConfig };
    }),
  }));

  return { version: 4, sections, lastModified: dashboard.lastModified };
};

const migrations: Record<number, MigrationFunction> = {
  2: migrateDashboardV1toV2,
  3: migrateDashboardV2toV3,
  4: migrateDashboardV3toV4,
};

export const migrateDashboardConfig = (
  config: DashboardState,
): DashboardState => {
  if (!isObject(config)) {
    console.error("Dashboard config is not an object");
    return generateEmptyDashboard();
  }

  const currentVersion = config.version || 0;
  let current: LegacyDashboard = { ...config };

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

  return current as DashboardState;
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
