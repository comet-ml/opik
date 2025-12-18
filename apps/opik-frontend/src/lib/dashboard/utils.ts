import uniqid from "uniqid";
import cloneDeep from "lodash/cloneDeep";
import map from "lodash/map";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import {
  BaseDashboardConfig,
  DashboardSection,
  DashboardSections,
  DashboardState,
  DashboardWidget,
  WIDGET_TYPE,
  AddWidgetConfig,
  WidgetResolver,
  TEMPLATE_TYPE,
} from "@/types/dashboard";
import { areLayoutsEqual } from "@/lib/dashboard/layout";
import { isLooseEqual } from "@/lib/utils";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";

export const DASHBOARD_VERSION = 1;
const DEFAULT_SECTION_NAME = "New section";

const TEMPLATE_ID_PREFIX = "template:";

export const UNSET_PROJECT_OPTION = [
  {
    value: "",
    label: "None",
  },
];

export const UNSET_PROJECT_VALUE = "";

export const GLOBAL_PROJECT_CONFIG_MESSAGE =
  "Using the dashboard's default project settings";

export const WIDGET_PROJECT_SELECTOR_DESCRIPTION =
  "Widgets use the dashboard's project settings by default. You can override them here and select a different project.";

export const resolveProjectIdFromConfig = (
  widgetProjectId: string | undefined,
  globalProjectId: string | undefined,
): {
  projectId: string | undefined;
  isUsingGlobalProject: boolean;
  infoMessage: string | undefined;
} => {
  const isUsingGlobalProject =
    isEmpty(widgetProjectId) && !isEmpty(globalProjectId);

  const projectId = !isEmpty(widgetProjectId)
    ? widgetProjectId
    : globalProjectId;

  const infoMessage = isUsingGlobalProject
    ? GLOBAL_PROJECT_CONFIG_MESSAGE
    : undefined;

  return {
    projectId,
    isUsingGlobalProject,
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
