import { describe, it, expect, vi } from "vitest";
import { migrateDashboardConfig } from "./migrations";
import { DashboardState, WIDGET_TYPE } from "@/types/dashboard";
import { DASHBOARD_VERSION, DEFAULT_MAX_EXPERIMENTS } from "./utils";

enum LEGACY_EXPERIMENT_DATA_SOURCE {
  FILTER_AND_GROUP = "filter_and_group",
  SELECT_EXPERIMENTS = "select_experiments",
}

const createLegacyDashboard = (
  version: number,
  overrides: Record<string, unknown> = {},
) => {
  return {
    version,
    sections: [
      {
        id: "section-1",
        title: "Section",
        widgets: [],
        layout: [],
      },
    ],
    lastModified: Date.now(),
    config: {
      dateRange: "7d",
      projectIds: [],
      experimentIds: [],
      experimentDataSource: LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
      experimentFilters: [],
      maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
    },
    ...overrides,
  } as unknown as DashboardState;
};

describe("migrateDashboardConfig", () => {
  describe("invalid inputs", () => {
    it("should return empty dashboard for non-object config", () => {
      const result = migrateDashboardConfig(null as unknown as DashboardState);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections).toHaveLength(1);
    });

    it("should return empty dashboard for undefined config", () => {
      const result = migrateDashboardConfig(
        undefined as unknown as DashboardState,
      );
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections).toHaveLength(1);
    });

    it("should return empty dashboard for string config", () => {
      const result = migrateDashboardConfig(
        "invalid" as unknown as DashboardState,
      );
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections).toHaveLength(1);
    });

    it("should return empty dashboard for number config", () => {
      const result = migrateDashboardConfig(123 as unknown as DashboardState);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections).toHaveLength(1);
    });

    it("should log error for non-object config", () => {
      const consoleErrorSpy = vi
        .spyOn(console, "error")
        .mockImplementation(() => {});
      migrateDashboardConfig(null as unknown as DashboardState);
      expect(consoleErrorSpy).toHaveBeenCalledWith(
        "Dashboard config is not an object",
      );
      consoleErrorSpy.mockRestore();
    });
  });

  describe("current version dashboard", () => {
    const currentDashboard: DashboardState = {
      version: DASHBOARD_VERSION,
      sections: [
        {
          id: "section-1",
          title: "Overview",
          widgets: [
            {
              id: "widget-1",
              type: WIDGET_TYPE.TEXT_MARKDOWN,
              title: "Test Widget",
              config: { content: "test" } as never,
            },
          ],
          layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 4 }],
        },
      ],
      lastModified: Date.now(),
    };

    it("should return dashboard unchanged when already at current version", () => {
      const result = migrateDashboardConfig(currentDashboard);
      expect(result).toEqual(currentDashboard);
    });

    it("should preserve all dashboard properties", () => {
      const result = migrateDashboardConfig(currentDashboard);
      expect(result.version).toBe(currentDashboard.version);
      expect(result.sections).toEqual(currentDashboard.sections);
      expect(result.lastModified).toBe(currentDashboard.lastModified);
    });

    it("should not have config property", () => {
      const result = migrateDashboardConfig(currentDashboard);
      expect(result).not.toHaveProperty("config");
    });
  });

  describe("legacy dashboard without version", () => {
    const legacyDashboard = createLegacyDashboard(0, {
      config: {
        dateRange: "30d",
        projectIds: ["legacy-project"],
        experimentIds: [],
        experimentDataSource: LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
        experimentFilters: [],
        maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
      },
    });

    it("should migrate to current version", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
    });

    it("should preserve legacy dashboard sections", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.sections[0].title).toBe("Section");
    });

    it("should not have config property after migration", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result).not.toHaveProperty("config");
    });
  });

  describe("old version dashboard", () => {
    it("should handle dashboard with version 0", () => {
      const oldDashboard = createLegacyDashboard(0);
      const result = migrateDashboardConfig(oldDashboard);
      expect(result.sections).toBeDefined();
      expect(result.version).toBe(DASHBOARD_VERSION);
    });
  });

  describe("future version dashboard", () => {
    it("should handle dashboard with version higher than current", () => {
      const futureDashboard: DashboardState = {
        version: DASHBOARD_VERSION + 5,
        sections: [
          {
            id: "section-1",
            title: "Future Section",
            widgets: [],
            layout: [],
          },
        ],
        lastModified: Date.now(),
      };

      const result = migrateDashboardConfig(futureDashboard);
      expect(result.sections).toBeDefined();
      expect(result).toEqual(futureDashboard);
    });
  });

  describe("edge cases", () => {
    it("should handle dashboard with empty sections", () => {
      const dashboard: DashboardState = {
        version: DASHBOARD_VERSION,
        sections: [],
        lastModified: Date.now(),
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toEqual([]);
    });

    it("should handle dashboard with complex widget configs", () => {
      const dashboard: DashboardState = {
        version: DASHBOARD_VERSION,
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  projectId: "project-1",
                  metricType: "trace_count",
                  chartType: "line",
                  traceFilters: [
                    {
                      field: "status",
                      operator: "=",
                      value: "success",
                      type: "string",
                    },
                  ],
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 3, h: 5 }],
          },
        ],
        lastModified: Date.now(),
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections[0].widgets[0].config).toEqual(
        dashboard.sections[0].widgets[0].config,
      );
    });

    it("should not mutate original dashboard", () => {
      const dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  projectId: "project-1",
                  metricType: "trace_count",
                  overrideDefaults: true,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: ["test"],
          experimentIds: [],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      });

      const originalCopy = JSON.parse(JSON.stringify(dashboard));
      migrateDashboardConfig(dashboard);

      expect(dashboard).toEqual(originalCopy);
    });

    it("should handle dashboard with multiple sections", () => {
      const dashboard: DashboardState = {
        version: DASHBOARD_VERSION,
        sections: [
          {
            id: "section-1",
            title: "Section 1",
            widgets: [],
            layout: [],
          },
          {
            id: "section-2",
            title: "Section 2",
            widgets: [],
            layout: [],
          },
          {
            id: "section-3",
            title: "Section 3",
            widgets: [],
            layout: [],
          },
        ],
        lastModified: Date.now(),
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toHaveLength(3);
      expect(result.sections[1].title).toBe("Section 2");
    });
  });

  describe("migration from v1 to v2 (through full pipeline)", () => {
    it("should migrate PROJECT_METRICS with custom projectId — preserves projectId", () => {
      const v1Dashboard = createLegacyDashboard(1, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  projectId: "project-1",
                  metricType: "trace_count",
                  chartType: "line",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 3, h: 5 }],
          },
        ],
      });

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.projectId).toBe("project-1");
      expect(
        result.sections[0].widgets[0].config.overrideDefaults,
      ).toBeUndefined();
    });

    it("should migrate PROJECT_METRICS without projectId — gets global projectId", () => {
      const v1Dashboard = createLegacyDashboard(1, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  metricType: "trace_count",
                  chartType: "line",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 3, h: 5 }],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: ["global-project"],
          experimentIds: [],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      });

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.projectId).toBe(
        "global-project",
      );
      expect(
        result.sections[0].widgets[0].config.overrideDefaults,
      ).toBeUndefined();
    });

    it("should not modify TEXT_MARKDOWN widgets", () => {
      const v1Dashboard = createLegacyDashboard(1, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.TEXT_MARKDOWN,
                title: "Text",
                config: { content: "test" },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 2 }],
          },
        ],
      });

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config).toEqual({
        content: "test",
      });
    });

    it("should handle multiple widgets of different types", () => {
      const v1Dashboard = createLegacyDashboard(1, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: { projectId: "project-1", metricType: "trace_count" },
              },
              {
                id: "widget-2",
                type: WIDGET_TYPE.TEXT_MARKDOWN,
                title: "Text",
                config: { content: "test" },
              },
              {
                id: "widget-3",
                type: WIDGET_TYPE.PROJECT_STATS_CARD,
                title: "Stats",
                config: { source: "traces" },
              },
            ],
            layout: [],
          },
        ],
      });

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      for (const widget of result.sections[0].widgets) {
        expect(widget.config.overrideDefaults).toBeUndefined();
      }
    });
  });

  describe("migration from v2 to v3 (through full pipeline)", () => {
    it("should add maxExperimentsCount to EXPERIMENTS_FEEDBACK_SCORES widgets", () => {
      const v2Dashboard = createLegacyDashboard(2, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                title: "Experiments",
                config: {
                  chartType: "line",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 4, h: 5 }],
          },
        ],
      });

      const result = migrateDashboardConfig(v2Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.maxExperimentsCount).toBe(10);
    });
  });

  describe("migration from v3 to v4", () => {
    it("should copy global projectId into project widgets with overrideDefaults=false", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  metricType: "trace_count",
                  overrideDefaults: false,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: ["global-project"],
          experimentIds: [],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.projectId).toBe(
        "global-project",
      );
      expect(
        result.sections[0].widgets[0].config.overrideDefaults,
      ).toBeUndefined();
      expect(result).not.toHaveProperty("config");
    });

    it("should keep widget projectId when overrideDefaults=true", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: {
                  projectId: "widget-project",
                  metricType: "trace_count",
                  overrideDefaults: true,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: ["global-project"],
          experimentIds: [],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      expect(result.sections[0].widgets[0].config.projectId).toBe(
        "widget-project",
      );
      expect(
        result.sections[0].widgets[0].config.overrideDefaults,
      ).toBeUndefined();
    });

    it("should convert SELECT_EXPERIMENTS experimentIds to filter when overrideDefaults=false", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                title: "Experiments",
                config: {
                  chartType: "line",
                  maxExperimentsCount: 10,
                  overrideDefaults: false,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: ["exp-1", "exp-2"],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [{ field: "name", operator: "=", value: "test" }],
          maxExperimentsCount: 25,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      const widgetConfig = result.sections[0].widgets[0].config;
      expect(widgetConfig.dataSource).toBeUndefined();
      expect(widgetConfig.experimentIds).toBeUndefined();
      expect(widgetConfig.filters).toEqual([
        {
          id: "experiment-ids-filter",
          field: "experiment_ids",
          type: "string",
          operator: "=",
          key: "",
          value: "exp-1,exp-2",
        },
      ]);
      expect(widgetConfig.groups).toEqual([]);
      expect(widgetConfig.overrideDefaults).toBeUndefined();
    });

    it("should keep filters when FILTER_AND_GROUP mode with overrideDefaults=false", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                title: "Experiments",
                config: {
                  chartType: "line",
                  maxExperimentsCount: 10,
                  overrideDefaults: false,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: LEGACY_EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
          experimentFilters: [{ field: "name", operator: "=", value: "test" }],
          maxExperimentsCount: 25,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      const widgetConfig = result.sections[0].widgets[0].config;
      expect(widgetConfig.dataSource).toBeUndefined();
      expect(widgetConfig.experimentIds).toBeUndefined();
      expect(widgetConfig.filters).toEqual([
        { field: "name", operator: "=", value: "test" },
      ]);
      expect(widgetConfig.maxExperimentsCount).toBe(25);
    });

    it("should convert experiment widget own values when overrideDefaults=true", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                title: "Experiments",
                config: {
                  chartType: "line",
                  maxExperimentsCount: 15,
                  dataSource: LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
                  filters: [{ field: "own", operator: "=", value: "filter" }],
                  experimentIds: ["own-exp"],
                  overrideDefaults: true,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: ["global-exp"],
          experimentDataSource: LEGACY_EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
          experimentFilters: [
            { field: "global", operator: "=", value: "filter" },
          ],
          maxExperimentsCount: 50,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      const widgetConfig = result.sections[0].widgets[0].config;
      expect(widgetConfig.dataSource).toBeUndefined();
      expect(widgetConfig.experimentIds).toBeUndefined();
      expect(widgetConfig.filters).toEqual([
        {
          id: "experiment-ids-filter",
          field: "experiment_ids",
          type: "string",
          operator: "=",
          key: "",
          value: "own-exp",
        },
      ]);
      expect(widgetConfig.groups).toEqual([]);
      expect(widgetConfig.maxExperimentsCount).toBe(15);
      expect(widgetConfig.overrideDefaults).toBeUndefined();
    });

    it("should convert leaderboard widget from global config when overrideDefaults=false", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENT_LEADERBOARD,
                title: "Leaderboard",
                config: {
                  enableRanking: true,
                  overrideDefaults: false,
                },
              },
            ],
            layout: [],
          },
        ],
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: ["exp-1"],
          experimentDataSource:
            LEGACY_EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: 50,
        },
      });

      const result = migrateDashboardConfig(v3Dashboard);
      const widgetConfig = result.sections[0].widgets[0].config;
      expect(widgetConfig.dataSource).toBeUndefined();
      expect(widgetConfig.experimentIds).toBeUndefined();
      expect(widgetConfig.filters).toEqual([
        {
          id: "experiment-ids-filter",
          field: "experiment_ids",
          type: "string",
          operator: "=",
          key: "",
          value: "exp-1",
        },
      ]);
      expect(widgetConfig.groups).toEqual([]);
      expect(widgetConfig.maxRows).toBe(50);
      expect(widgetConfig.overrideDefaults).toBeUndefined();
    });

    it("should strip overrideDefaults from all widgets", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_METRICS,
                title: "Metrics",
                config: { metricType: "trace_count", overrideDefaults: true },
              },
              {
                id: "widget-2",
                type: WIDGET_TYPE.PROJECT_STATS_CARD,
                title: "Stats",
                config: {
                  source: "traces",
                  metric: "count",
                  overrideDefaults: false,
                },
              },
              {
                id: "widget-3",
                type: WIDGET_TYPE.TEXT_MARKDOWN,
                title: "Text",
                config: { content: "test" },
              },
            ],
            layout: [],
          },
        ],
      });

      const result = migrateDashboardConfig(v3Dashboard);
      for (const widget of result.sections[0].widgets) {
        expect(widget.config.overrideDefaults).toBeUndefined();
      }
    });

    it("should remove config property from dashboard", () => {
      const v3Dashboard = createLegacyDashboard(3);
      const result = migrateDashboardConfig(v3Dashboard);
      expect(result).not.toHaveProperty("config");
    });

    it("should keep FILTER_AND_GROUP with empty experimentIds as-is (no experiment_ids filter)", () => {
      const v3Dashboard = createLegacyDashboard(3, {
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                title: "Experiments",
                config: {
                  chartType: "line",
                  dataSource: LEGACY_EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
                  filters: [],
                  experimentIds: [],
                  overrideDefaults: true,
                },
              },
            ],
            layout: [],
          },
        ],
      });

      const result = migrateDashboardConfig(v3Dashboard);
      const widgetConfig = result.sections[0].widgets[0].config;
      expect(widgetConfig.dataSource).toBeUndefined();
      expect(widgetConfig.experimentIds).toBeUndefined();
      expect(widgetConfig.filters).toEqual([]);
    });
  });
});
