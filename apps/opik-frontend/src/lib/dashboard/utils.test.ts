import { describe, it, expect, vi } from "vitest";
import {
  generateEmptySection,
  generateEmptyDashboard,
  getSectionById,
  areWidgetsEqual,
  areWidgetArraysEqual,
  areSectionsEqual,
  areSectionArraysEqual,
  isDashboardChanged,
  createDefaultWidgetConfig,
  DASHBOARD_VERSION,
  DEFAULT_MAX_EXPERIMENTS,
} from "./utils";
import {
  DashboardWidget,
  DashboardSection,
  DashboardState,
  WIDGET_TYPE,
  WidgetResolver,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";

describe("generateEmptySection", () => {
  it("should create section with default title", () => {
    const section = generateEmptySection();
    expect(section.title).toBe("New section");
    expect(section.widgets).toEqual([]);
    expect(section.layout).toEqual([]);
    expect(section.id).toBeTruthy();
  });

  it("should create section with custom title", () => {
    const section = generateEmptySection("Custom Title");
    expect(section.title).toBe("Custom Title");
  });

  it("should create section with unique ID", () => {
    const section1 = generateEmptySection();
    const section2 = generateEmptySection();
    expect(section1.id).not.toBe(section2.id);
  });
});

describe("generateEmptyDashboard", () => {
  it("should create dashboard with current version", () => {
    const dashboard = generateEmptyDashboard();
    expect(dashboard.version).toBe(DASHBOARD_VERSION);
  });

  it("should create dashboard with one section", () => {
    const dashboard = generateEmptyDashboard();
    expect(dashboard.sections).toHaveLength(1);
    expect(dashboard.sections[0].title).toBe("Overview");
  });

  it("should create dashboard with timestamp", () => {
    const before = Date.now();
    const dashboard = generateEmptyDashboard();
    const after = Date.now();
    expect(dashboard.lastModified).toBeGreaterThanOrEqual(before);
    expect(dashboard.lastModified).toBeLessThanOrEqual(after);
  });

  it("should create dashboard with default config", () => {
    const dashboard = generateEmptyDashboard();
    expect(dashboard.config).toBeDefined();
    expect(dashboard.config.projectIds).toEqual([]);
    expect(dashboard.config.experimentIds).toEqual([]);
    expect(dashboard.config.dateRange).toBeDefined();
  });
});

describe("getSectionById", () => {
  const sections: DashboardSection[] = [
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
  ];

  it("should find section by ID", () => {
    const section = getSectionById(sections, "section-1");
    expect(section).toBeDefined();
    expect(section?.title).toBe("Section 1");
  });

  it("should return undefined for non-existent ID", () => {
    const section = getSectionById(sections, "non-existent");
    expect(section).toBeUndefined();
  });

  it("should return undefined for empty array", () => {
    const section = getSectionById([], "section-1");
    expect(section).toBeUndefined();
  });
});

describe("areWidgetsEqual", () => {
  const baseWidget: DashboardWidget = {
    id: "widget-1",
    type: WIDGET_TYPE.TEXT_MARKDOWN,
    title: "Test Widget",
    generatedTitle: "Text",
    subtitle: "Subtitle",
    config: { content: "test" } as never,
  };

  it("should return true for identical widgets", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget };
    expect(areWidgetsEqual(widget1, widget2)).toBe(true);
  });

  it("should return false for different IDs", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget, id: "widget-2" };
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should return false for different types", () => {
    const widget1 = { ...baseWidget };
    const widget2 = {
      ...baseWidget,
      type: WIDGET_TYPE.PROJECT_METRICS,
    } as DashboardWidget;
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should return false for different titles", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget, title: "Different Title" };
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should return false for different generatedTitles", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget, generatedTitle: "Different Generated" };
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should return false for different subtitles", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget, subtitle: "Different Subtitle" };
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should handle undefined subtitles", () => {
    const widget1 = { ...baseWidget, subtitle: undefined };
    const widget2 = { ...baseWidget, subtitle: undefined };
    expect(areWidgetsEqual(widget1, widget2)).toBe(true);
  });

  it("should return false for different configs", () => {
    const widget1 = { ...baseWidget };
    const widget2 = { ...baseWidget, config: { content: "different" } };
    expect(areWidgetsEqual(widget1, widget2)).toBe(false);
  });

  it("should handle complex nested configs", () => {
    const widget1 = {
      ...baseWidget,
      config: { nested: { deep: { value: 123 } } } as never,
    };
    const widget2 = {
      ...baseWidget,
      config: { nested: { deep: { value: 123 } } } as never,
    };
    expect(areWidgetsEqual(widget1, widget2)).toBe(true);
  });
});

describe("areWidgetArraysEqual", () => {
  const widget1: DashboardWidget = {
    id: "widget-1",
    type: WIDGET_TYPE.TEXT_MARKDOWN,
    title: "Widget 1",
    generatedTitle: "Text",
    config: {},
  };

  const widget2: DashboardWidget = {
    id: "widget-2",
    type: WIDGET_TYPE.TEXT_MARKDOWN,
    title: "Widget 2",
    generatedTitle: "Text",
    config: {},
  };

  it("should return true for empty arrays", () => {
    expect(areWidgetArraysEqual([], [])).toBe(true);
  });

  it("should return true for identical arrays", () => {
    expect(areWidgetArraysEqual([widget1, widget2], [widget1, widget2])).toBe(
      true,
    );
  });

  it("should return true regardless of order", () => {
    expect(areWidgetArraysEqual([widget1, widget2], [widget2, widget1])).toBe(
      true,
    );
  });

  it("should return false for different lengths", () => {
    expect(areWidgetArraysEqual([widget1], [widget1, widget2])).toBe(false);
  });

  it("should return false for different widgets", () => {
    const widget3 = { ...widget1, title: "Different" };
    expect(areWidgetArraysEqual([widget1], [widget3])).toBe(false);
  });
});

describe("areSectionsEqual", () => {
  const widget1: DashboardWidget = {
    id: "widget-1",
    type: WIDGET_TYPE.TEXT_MARKDOWN,
    title: "Widget 1",
    generatedTitle: "Text",
    config: {},
  };

  const baseSection: DashboardSection = {
    id: "section-1",
    title: "Section 1",
    widgets: [widget1],
    layout: [{ i: "widget-1", x: 0, y: 0, w: 2, h: 2 }],
  };

  it("should return true for identical sections", () => {
    const section1 = { ...baseSection };
    const section2 = { ...baseSection };
    expect(areSectionsEqual(section1, section2)).toBe(true);
  });

  it("should return false for different IDs", () => {
    const section1 = { ...baseSection };
    const section2 = { ...baseSection, id: "section-2" };
    expect(areSectionsEqual(section1, section2)).toBe(false);
  });

  it("should return false for different titles", () => {
    const section1 = { ...baseSection };
    const section2 = { ...baseSection, title: "Different Title" };
    expect(areSectionsEqual(section1, section2)).toBe(false);
  });

  it("should return false for different widgets", () => {
    const section1 = { ...baseSection };
    const section2 = { ...baseSection, widgets: [] };
    expect(areSectionsEqual(section1, section2)).toBe(false);
  });

  it("should return false for different layouts", () => {
    const section1 = { ...baseSection };
    const section2 = {
      ...baseSection,
      layout: [{ i: "widget-1", x: 1, y: 0, w: 2, h: 2 }],
    };
    expect(areSectionsEqual(section1, section2)).toBe(false);
  });
});

describe("areSectionArraysEqual", () => {
  const section1: DashboardSection = {
    id: "section-1",
    title: "Section 1",
    widgets: [],
    layout: [],
  };

  const section2: DashboardSection = {
    id: "section-2",
    title: "Section 2",
    widgets: [],
    layout: [],
  };

  it("should return true for empty arrays", () => {
    expect(areSectionArraysEqual([], [])).toBe(true);
  });

  it("should return true for identical arrays", () => {
    expect(areSectionArraysEqual([section1], [section1])).toBe(true);
  });

  it("should return false for different lengths", () => {
    expect(areSectionArraysEqual([section1], [section1, section2])).toBe(false);
  });

  it("should respect order of sections", () => {
    expect(
      areSectionArraysEqual([section1, section2], [section2, section1]),
    ).toBe(false);
  });

  it("should return false for different sections", () => {
    const section3 = { ...section1, title: "Different" };
    expect(areSectionArraysEqual([section1], [section3])).toBe(false);
  });
});

describe("isDashboardChanged", () => {
  const baseDashboard: DashboardState = {
    version: 1,
    sections: [
      {
        id: "section-1",
        title: "Section 1",
        widgets: [],
        layout: [],
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

  it("should return true when previous is null", () => {
    expect(isDashboardChanged(baseDashboard, null)).toBe(true);
  });

  it("should return false for identical dashboards", () => {
    const current = { ...baseDashboard };
    const previous = { ...baseDashboard };
    expect(isDashboardChanged(current, previous)).toBe(false);
  });

  it("should return true for different versions", () => {
    const current = { ...baseDashboard, version: 2 };
    const previous = { ...baseDashboard, version: 1 };
    expect(isDashboardChanged(current, previous)).toBe(true);
  });

  it("should return true for different sections", () => {
    const current = { ...baseDashboard };
    const previous = {
      ...baseDashboard,
      sections: [],
    };
    expect(isDashboardChanged(current, previous)).toBe(true);
  });

  it("should return true for different configs", () => {
    const current = {
      ...baseDashboard,
      config: {
        dateRange: "7d",
        projectIds: ["project-2"],
        experimentIds: [],
        experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
        experimentFilters: [],
        maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
      },
    };
    const previous = { ...baseDashboard };
    expect(isDashboardChanged(current, previous)).toBe(true);
  });

  it("should ignore lastModified timestamp", () => {
    const current = { ...baseDashboard, lastModified: Date.now() + 1000 };
    const previous = { ...baseDashboard };
    expect(isDashboardChanged(current, previous)).toBe(false);
  });
});

describe("createDefaultWidgetConfig", () => {
  it("should create config without resolver", () => {
    const config = createDefaultWidgetConfig(WIDGET_TYPE.TEXT_MARKDOWN, null);
    expect(config.type).toBe(WIDGET_TYPE.TEXT_MARKDOWN);
    expect(config.title).toBe("");
    expect(config.generatedTitle).toBe("");
    expect(config.subtitle).toBe("");
    expect(config.config).toEqual({});
  });

  it("should create config with resolver", () => {
    const mockResolver: WidgetResolver = vi.fn(() => ({
      Widget: () => null,
      Editor: null,
      getDefaultConfig: () => ({ content: "default" }),
      calculateTitle: () => "Generated Title",
      metadata: {
        title: "Test Widget",
        description: "Test",
        icon: null,
        category: "general" as never,
      },
    }));

    const config = createDefaultWidgetConfig(
      WIDGET_TYPE.TEXT_MARKDOWN,
      mockResolver,
    );

    expect(config.type).toBe(WIDGET_TYPE.TEXT_MARKDOWN);
    expect(config.title).toBe("");
    expect(config.generatedTitle).toBe("Generated Title");
    expect(config.subtitle).toBe("");
    expect(config.config).toEqual({ content: "default" });
    expect(mockResolver).toHaveBeenCalledWith({
      type: WIDGET_TYPE.TEXT_MARKDOWN,
    });
  });
});
