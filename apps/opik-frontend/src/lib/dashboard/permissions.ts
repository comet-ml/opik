import { WIDGET_TYPE, WidgetComponents } from "@/types/dashboard";
import { WIDGET_TYPES } from "@/lib/dashboard/utils";
import { Permissions } from "@/types/permissions";
import { DISABLED_EXPERIMENTS_TOOLTIP } from "@/constants/permissions";

const EXPERIMENT_WIDGET_TYPES = new Set<WIDGET_TYPE>([
  WIDGET_TYPES.EXPERIMENTS_FEEDBACK_SCORES,
  WIDGET_TYPES.EXPERIMENT_LEADERBOARD,
]);

export const isExperimentWidgetType = (widgetType: string): boolean => {
  return EXPERIMENT_WIDGET_TYPES.has(widgetType as WIDGET_TYPE);
};

export const applyWidgetPermissions = (
  components: WidgetComponents,
  widgetType: string,
  permissions: Permissions,
): WidgetComponents => {
  const { canViewExperiments } = permissions;

  if (isExperimentWidgetType(widgetType) && !canViewExperiments) {
    return {
      ...components,
      metadata: {
        ...components.metadata,
        disabled: true,
        disabledTooltip: DISABLED_EXPERIMENTS_TOOLTIP,
      },
    };
  }

  return components;
};
