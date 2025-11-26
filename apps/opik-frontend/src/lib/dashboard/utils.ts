import {
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  WIDGET_TYPE,
} from "@/types/dashboard";

const DEFAULT_SECTION_NAME = "New section";
const DEFAULT_WIDGET_TITLE = "Widget";

export const WIDGET_TYPES = WIDGET_TYPE;

export const generateId = (): string => {
  return Math.random().toString(36).substring(2, 9);
};

export const generateEmptyWidget = (
  type: WIDGET_TYPE | string = WIDGET_TYPE.CHART_METRIC,
  title?: string,
  subtitle?: string,
  config?: Record<string, unknown>,
): DashboardWidget => {
  return {
    id: generateId(),
    type,
    title: title || DEFAULT_WIDGET_TITLE,
    subtitle,
    config: config || {},
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
