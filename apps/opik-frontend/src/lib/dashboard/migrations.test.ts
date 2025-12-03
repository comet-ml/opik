import { describe, it, expect, vi } from "vitest";
import { migrateDashboardConfig } from "./migrations";
import { DashboardState, WIDGET_TYPE } from "@/types/dashboard";
import { DASHBOARD_VERSION } from "./utils";

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
      config: { projectId: "project-1", dateRange: "7d" },
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
      config: { projectId: "legacy-project", dateRange: "30d" },
    } as DashboardState;

    it("should treat dashboard without version as version 0", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.sections).toEqual(legacyDashboard.sections);
    });

    it("should preserve legacy dashboard data", () => {
      const result = migrateDashboardConfig(legacyDashboard);
      expect(result.sections[0].title).toBe("Legacy Section");
      expect(result.config).toEqual(legacyDashboard.config);
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
        config: {},
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
        config: {},
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
        config: {},
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toEqual([]);
    });

    it("should handle dashboard with empty config", () => {
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
        config: {},
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.config).toEqual({});
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
        config: { projectId: "project-1", dateRange: "7d" },
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
        config: { projectId: "test" },
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
        config: {},
      };

      const result = migrateDashboardConfig(dashboard);
      expect(result.sections).toHaveLength(3);
      expect(result.sections[1].title).toBe("Section 2");
    });
  });
});
