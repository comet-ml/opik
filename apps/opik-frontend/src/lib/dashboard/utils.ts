import {
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
} from "@/types/dashboard";

const DEFAULT_SECTION_NAME = "New section";
const DEFAULT_WIDGET_TITLE = "Widget";

export const WIDGET_TYPES = {
  CHART_METRIC: "chart",
  STAT_CARD: "stat_card",
  TEXT_MARKDOWN: "text_markdown",
  COST_SUMMARY: "cost_summary",
} as const;

export const generateId = (): string => {
  return Math.random().toString(36).substring(2, 9);
};

export const generateEmptyWidget = (
  type = "chart",
  title?: string,
  metricType?: string,
  subtitle?: string,
): DashboardWidget => {
  return {
    id: generateId(),
    type,
    title: title || DEFAULT_WIDGET_TITLE,
    subtitle,
    metricType,
    config: {},
  };
};

export const generateEmptySection = (
  title = DEFAULT_SECTION_NAME,
): DashboardSection => {
  return {
    id: generateId(),
    title,
    expanded: true,
    widgets: [],
    layout: [],
  };
};

export const generateEmptyDashboard = (): DashboardState => {
  const defaultSection = generateEmptySection("Overview");

  return {
    version: 1,
    sections: [defaultSection],
    lastModified: Date.now(),
  };
};

export const getSectionById = (
  sections: DashboardSections,
  id: string,
): DashboardSection | undefined => {
  return sections.find((section) => section.id === id);
};
