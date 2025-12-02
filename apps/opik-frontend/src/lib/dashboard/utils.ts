import uniqid from "uniqid";
import {
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  WIDGET_TYPE,
  AddWidgetConfig,
  WidgetResolver,
} from "@/types/dashboard";
import { areLayoutsEqual } from "@/lib/dashboard/layout";
import { isLooseEqual } from "@/lib/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";

export const DASHBOARD_VERSION = 1;
const DEFAULT_SECTION_NAME = "New section";

export const WIDGET_TYPES = WIDGET_TYPE;

export const generateEmptySection = (
  title = DEFAULT_SECTION_NAME,
): DashboardSection => {
  return {
    id: uniqid(),
    title,
    widgets: [],
    layout: [],
  };
};

export const generateEmptyDashboard = (): DashboardState => {
  const defaultSection = generateEmptySection("Overview");

  return {
    version: DASHBOARD_VERSION,
    sections: [defaultSection],
    lastModified: Date.now(),
    config: {
      projectId: "",
      dateRange: DEFAULT_DATE_PRESET,
    },
  };
};

export const getSectionById = (
  sections: DashboardSections,
  id: string,
): DashboardSection | undefined => {
  return sections.find((section) => section.id === id);
};

export const areWidgetsEqual = (
  prev: DashboardWidget,
  next: DashboardWidget,
): boolean => {
  return (
    prev.id === next.id &&
    prev.type === next.type &&
    prev.title === next.title &&
    prev.subtitle === next.subtitle &&
    isLooseEqual(prev.config, next.config)
  );
};

export const areWidgetArraysEqual = (
  prev: DashboardWidget[],
  next: DashboardWidget[],
): boolean => {
  if (prev.length !== next.length) return false;

  const sortedPrev = [...prev].sort((a, b) => a.id.localeCompare(b.id));
  const sortedNext = [...next].sort((a, b) => a.id.localeCompare(b.id));

  return sortedPrev.every((widget, i) =>
    areWidgetsEqual(widget, sortedNext[i]),
  );
};

export const areSectionsEqual = (
  prev: DashboardSection,
  next: DashboardSection,
): boolean => {
  return (
    prev.id === next.id &&
    prev.title === next.title &&
    areWidgetArraysEqual(prev.widgets, next.widgets) &&
    areLayoutsEqual(prev.layout, next.layout)
  );
};

export const areSectionArraysEqual = (
  prev: DashboardSections,
  next: DashboardSections,
): boolean => {
  if (prev.length !== next.length) return false;

  return prev.every((section, i) => areSectionsEqual(section, next[i]));
};

export const isDashboardChanged = (
  current: DashboardState,
  previous: DashboardState | null,
): boolean => {
  if (!previous) return true;

  if (current.version !== previous.version) return true;

  if (!areSectionArraysEqual(current.sections, previous.sections)) return true;

  return !isLooseEqual(current.config, previous.config);
};

export const createDefaultWidgetConfig = (
  widgetType: string,
  widgetResolver: WidgetResolver | null,
): AddWidgetConfig => {
  if (!widgetResolver) {
    return {
      type: widgetType as WIDGET_TYPE,
      title: "",
      subtitle: "",
      config: {},
    } as AddWidgetConfig;
  }

  const widgetComponents = widgetResolver(widgetType);
  const defaultConfig = widgetComponents.getDefaultConfig();
  const defaultTitle = widgetComponents.calculateTitle(defaultConfig);

  return {
    type: widgetType as WIDGET_TYPE,
    title: defaultTitle,
    subtitle: "",
    config: defaultConfig,
  } as AddWidgetConfig;
};
