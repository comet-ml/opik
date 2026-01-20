import { describe, it, expect, vi } from "vitest";
import { migrateDashboardConfig } from "./migrations";
import {
  DashboardState,
  WIDGET_TYPE,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import { DASHBOARD_VERSION, DEFAULT_MAX_EXPERIMENTS } from "./utils";

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
      config: {
        dateRange: "7d",
        projectIds: ["project-1"],
        experimentIds: [],
        experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
        experimentFilters: [],
        maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
      },
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
      expect(result.config).toEqual(currentDashboard.config);
    });
  });

  describe("legacy dashboard without version", () => {
    const legacyDashboard = {
      version: 0,
      sections: [
        {
          id: "section-1",
          title: "Legacy Section",
          widgets: [],
          layout: [],
        },
      ],
      lastModified: Date.now(),
      config: {
        dateRange: "30d",
        projectIds: ["legacy-project"],
        experimentIds: [],
        experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
        experimentFilters: [],
        maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
      },
    } as DashboardState;

    it("should treat dashboard without version as version 0", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.sections).toEqual(legacyDashboard.sections);
    });

    it("should preserve legacy dashboard data", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.sections[0].title).toBe("Legacy Section");
      expect(result.config.dateRange).toBe(legacyDashboard.config.dateRange);
      expect(result.config.projectIds).toEqual(
        legacyDashboard.config.projectIds,
      );
      expect(result.config.experimentIds).toEqual(
        legacyDashboard.config.experimentIds,
      );
      // Migration adds new fields
      expect(result.config.experimentDataSource).toBe(
        EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
      );
      expect(result.config.experimentFilters).toEqual([]);
      expect(result.config.maxExperimentsCount).toBe(10);
    });
  });

  describe("old version dashboard", () => {
    it("should handle dashboard with version 0", () => {
      const oldDashboard: DashboardState = {
        version: 0,
        sections: [
          {
            id: "section-1",
            title: "Old Section",
            widgets: [],
            layout: [],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(oldDashboard);
      expect(result.sections).toBeDefined();
      expect(result.version).toBeDefined();
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
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
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
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toEqual([]);
    });

    it("should handle dashboard with default config", () => {
      const dashboard: DashboardState = {
        version: DASHBOARD_VERSION,
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
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.config).toEqual(dashboard.config);
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
        config: {
          dateRange: "7d",
          projectIds: ["project-1"],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections[0].widgets[0].config).toEqual(
        dashboard.sections[0].widgets[0].config,
      );
    });

    it("should not mutate original dashboard", () => {
      const dashboard: DashboardState = {
        version: DASHBOARD_VERSION,
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
          projectIds: ["test"],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

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
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toHaveLength(3);
      expect(result.sections[1].title).toBe("Section 2");
    });
  });

  describe("migration from v1 to v2", () => {
    it("should set overrideDefaults to true for PROJECT_METRICS widgets with custom projectId", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(true);
    });

    it("should set overrideDefaults to false for PROJECT_METRICS widgets without custom projectId", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(false);
    });

    it("should set overrideDefaults to true for PROJECT_STATS_CARD widgets with custom projectId", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_STATS_CARD,
                title: "Stats",
                config: {
                  projectId: "project-1",
                  source: "traces",
                  metric: "count",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 3 }],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(true);
    });

    it("should set overrideDefaults to false for PROJECT_STATS_CARD widgets without custom projectId", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.PROJECT_STATS_CARD,
                title: "Stats",
                config: {
                  source: "traces",
                  metric: "count",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 3 }],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(false);
    });

    it("should set overrideDefaults to true when experimentIds exist", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
                  experimentIds: ["exp-1"],
                  chartType: "line",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 4, h: 5 }],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(true);
    });

    it("should set overrideDefaults to true when dataSource is not SELECT_EXPERIMENTS", () => {
      const testCases = [
        {
          name: "undefined dataSource",
          config: { chartType: "line" },
        },
        {
          name: "FILTER_AND_GROUP dataSource",
          config: {
            dataSource: "filter-and-group",
            chartType: "line",
            filters: [],
            groups: [],
          },
        },
      ];

      testCases.forEach(({ name, config }) => {
        const v1Dashboard: DashboardState = {
          version: 1,
          sections: [
            {
              id: "section-1",
              title: "Section",
              widgets: [
                {
                  id: "widget-1",
                  type: WIDGET_TYPE.EXPERIMENTS_FEEDBACK_SCORES,
                  title: "Experiments",
                  config,
                },
              ],
              layout: [{ i: "widget-1", x: 0, y: 0, w: 4, h: 5 }],
            },
          ],
          lastModified: Date.now(),
          config: {
            dateRange: "7d",
            projectIds: [],
            experimentIds: [],
            experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
            experimentFilters: [],
            maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
          },
        };

        const result = migrateDashboardConfig(v1Dashboard);
        expect(result.version).toBe(DASHBOARD_VERSION);
        expect(
          result.sections[0].widgets[0].config.overrideDefaults,
          `Failed for ${name}`,
        ).toBe(true);
      });
    });

    it("should set overrideDefaults to false when dataSource is SELECT_EXPERIMENTS and no experimentIds", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
                  dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
                  chartType: "line",
                },
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 4, h: 5 }],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(false);
    });

    it("should not modify TEXT_MARKDOWN widgets", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
        sections: [
          {
            id: "section-1",
            title: "Section",
            widgets: [
              {
                id: "widget-1",
                type: WIDGET_TYPE.TEXT_MARKDOWN,
                title: "Text",
                config: { content: "test" } as never,
              },
            ],
            layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 2 }],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config).toEqual({
        content: "test",
      });
    });

    it("should handle multiple widgets of different types", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
                config: { content: "test" } as never,
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
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.sections[0].widgets[0].config.overrideDefaults).toBe(true);
      expect(
        result.sections[0].widgets[1].config.overrideDefaults,
      ).toBeUndefined();
      expect(result.sections[0].widgets[2].config.overrideDefaults).toBe(false);
    });

    it("should preserve existing widget config properties", () => {
      const v1Dashboard: DashboardState = {
        version: 1,
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
                  feedbackScores: ["score-1"],
                },
              },
            ],
            layout: [],
          },
        ],
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v1Dashboard);
      expect(result.sections[0].widgets[0].config).toEqual({
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
        feedbackScores: ["score-1"],
        overrideDefaults: true,
      });
    });
  });

  describe("migration from v2 to v3", () => {
    it("should migrate dashboard from version 2 to 3 and add default config fields", () => {
      const v2Dashboard: DashboardState = {
        version: 2,
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
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v2Dashboard);
      expect(result.version).toBe(DASHBOARD_VERSION);
      expect(result.config.experimentDataSource).toBe(
        EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
      );
      expect(result.config.experimentFilters).toEqual([]);
      expect(result.config.maxExperimentsCount).toBe(10);
    });

    it("should preserve existing config values", () => {
      const v2Dashboard: DashboardState = {
        version: 2,
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
          experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
          experimentFilters: [],
          maxExperimentsCount: 25,
        },
      };

      const result = migrateDashboardConfig(v2Dashboard);
      expect(result.config.experimentDataSource).toBe(
        EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      );
      expect(result.config.maxExperimentsCount).toBe(25);
    });

    it("should add maxExperimentsCount to EXPERIMENTS_FEEDBACK_SCORES widgets", () => {
      const v2Dashboard: DashboardState = {
        version: 2,
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
        lastModified: Date.now(),
        config: {
          dateRange: "7d",
          projectIds: [],
          experimentIds: [],
          experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
          experimentFilters: [],
          maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
        },
      };

      const result = migrateDashboardConfig(v2Dashboard);
      expect(result.sections[0].widgets[0].config.maxExperimentsCount).toBe(10);
    });
  });
});
