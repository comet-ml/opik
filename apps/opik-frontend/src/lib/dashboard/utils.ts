import uniqid from "uniqid";
import cloneDeep from "lodash/cloneDeep";
import map from "lodash/map";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import isString from "lodash/isString";
import {
  BaseDashboardConfig,
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  WIDGET_TYPE,
  WidgetResolver,
  TEMPLATE_TYPE,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import { areLayoutsEqual } from "@/lib/dashboard/layout";
import { isLooseEqual } from "@/lib/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";

export const DASHBOARD_VERSION = 3;
export const MIN_MAX_EXPERIMENTS = 1;
export const MAX_MAX_EXPERIMENTS = 100;
export const DEFAULT_MAX_EXPERIMENTS = 10;

const DEFAULT_SECTION_NAME = "New section";

const TEMPLATE_ID_PREFIX = "template:";

export const CUSTOM_PROJECT_CONFIG_MESSAGE =
  "This widget uses a custom project instead of the dashboard default.";

export const resolveProjectIdFromConfig = (
  widgetProjectId: string | undefined,
  globalProjectId: string | undefined,
  overrideDefaults?: boolean,
): {
  projectId: string | undefined;
  infoMessage: string | undefined;
} => {
  // If overrideDefaults is true, use widget's own projectId
  // Otherwise, always use global projectId
  const projectId = overrideDefaults ? widgetProjectId : globalProjectId;

  const infoMessage = overrideDefaults
    ? CUSTOM_PROJECT_CONFIG_MESSAGE
    : undefined;

  return {
    projectId,
    infoMessage,
  };
};

export const createTemplateId = (templateId: TEMPLATE_TYPE): string => {
  return `${TEMPLATE_ID_PREFIX}${templateId}`;
};

export const isTemplateId = (id: string | null): boolean => {
  return id?.startsWith(TEMPLATE_ID_PREFIX) ?? false;
};

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

export const generateEmptyConfig = (): BaseDashboardConfig => ({
  dateRange: DEFAULT_DATE_PRESET,
  projectIds: [],
  experimentIds: [],
  experimentDataSource: EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
  experimentFilters: [],
  maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
});

export const generateEmptyDashboard = (): DashboardState => {
  const defaultSection = generateEmptySection("Overview");

  return {
    version: DASHBOARD_VERSION,
    sections: [defaultSection],
    lastModified: Date.now(),
    config: generateEmptyConfig(),
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
    prev.generatedTitle === next.generatedTitle &&
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
): Omit<DashboardWidget, "id"> => {
  if (!widgetResolver) {
    return {
      type: widgetType as WIDGET_TYPE,
      title: "",
      generatedTitle: "",
      subtitle: "",
      config: {},
    };
  }

  const widgetComponents = widgetResolver({ type: widgetType });
  const defaultConfig = widgetComponents.getDefaultConfig();
  const generatedTitle = widgetComponents.calculateTitle(defaultConfig);

  return {
    type: widgetType as WIDGET_TYPE,
    title: "",
    generatedTitle: generatedTitle,
    subtitle: "",
    config: defaultConfig,
  };
};

export const regenerateAllIds = (
  dashboardState: DashboardState,
): DashboardState => {
  const clonedConfig: BaseDashboardConfig = cloneDeep(
    get(dashboardState, "config"),
  );

  const newSections: DashboardSections = map(
    get(dashboardState, "sections", []),
    (section) => {
      const newSectionId = uniqid();

      const widgetIdMap = new Map<string, string>();

      const newWidgets: DashboardWidget[] = map(
        get(section, "widgets", []),
        (widget) => {
          const newWidgetId = uniqid();
          const oldWidgetId = get(widget, "id");
          widgetIdMap.set(oldWidgetId, newWidgetId);

          return {
            ...widget,
            id: newWidgetId,
            config: cloneDeep(get(widget, "config", {})),
          };
        },
      );

      const newLayout = map(get(section, "layout", []), (layoutItem) => {
        const oldWidgetId = get(layoutItem, "i");
        const newWidgetId = widgetIdMap.get(oldWidgetId);

        return {
          ...cloneDeep(layoutItem),
          i: newWidgetId || oldWidgetId,
        };
      });

      return {
        id: newSectionId,
        title: get(section, "title", ""),
        widgets: newWidgets,
        layout: newLayout,
      };
    },
  );

  return {
    version: get(dashboardState, "version", DASHBOARD_VERSION),
    sections: newSections,
    lastModified: Date.now(),
    config: clonedConfig,
  };
};

export const updateWidgetWithGeneratedTitle = (
  currentWidget: DashboardWidget,
  updates: Partial<DashboardWidget>,
  widgetResolver: WidgetResolver | null,
): DashboardWidget => {
  const merged = {
    ...currentWidget,
    ...updates,
    config:
      updates.config !== undefined
        ? { ...currentWidget.config, ...updates.config }
        : currentWidget.config,
  };

  const generatedTitle = widgetResolver
    ? widgetResolver({ type: merged.type }).calculateTitle(merged.config)
    : "";

  return {
    ...merged,
    generatedTitle,
  };
};

export const isValidIntegerInRange = (
  value: string,
  min: number,
  max: number,
): boolean => {
  if (!isString(value) || isEmpty(value)) {
    return false;
  }
  const trimmedValue = value.trim();
  if (isEmpty(trimmedValue)) {
    return false;
  }
  const numValue = Number(trimmedValue);
  return (
    Number.isInteger(numValue) &&
    String(numValue) === trimmedValue &&
    numValue >= min &&
    numValue <= max
  );
};
