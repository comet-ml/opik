import { useCallback } from "react";
import { v4 as uuidv4 } from "uuid";
import { useDashboardStore, selectRuntimeConfig } from "@/store/DashboardStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { isTemplateId } from "@/lib/dashboard/utils";

// Where the user asked Ollie for a widget, reported to BI to compare the entry points.
export type AskOllieForWidgetSource =
  | "add_widget_menu"
  | "empty_state"
  | "widget_dialog";

const DESCRIBE_WIDGET_MESSAGE =
  "I want to add a custom widget to this dashboard. Ask me what it should show.";
// Ollie cannot write to a built-in view, so the chart goes to one of the project's own dashboards.
const DESCRIBE_TEMPLATE_WIDGET_MESSAGE =
  "I want to add a custom widget to this project. The dashboard I have open is a built-in view that can't be edited, so add the chart to a new or existing project dashboard. Ask me what it should show.";

const useAskOllieForWidget = () => {
  const runtimeConfig = useDashboardStore(selectRuntimeConfig);
  const getSectionById = useDashboardStore((state) => state.getSectionById);

  // The sidebar plugin is what answers the bridge; without it the request would go nowhere.
  const assistantAvailable = usePluginsStore((state) =>
    Boolean(state.AssistantSidebar),
  );
  // Ollie can only build a widget when Custom Charts is enabled.
  const customChartsEnabledForWorkspace = useIsFeatureEnabled(
    FeatureToggleKeys.CUSTOM_CHARTS_ENABLED_FOR_WORKSPACE,
  );
  const isOllieAvailable =
    useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED) &&
    customChartsEnabledForWorkspace &&
    assistantAvailable;

  // Hands the request to Ollie, which draws it and offers "Add to <this dashboard>" on the chart. The section the
  // user asked from goes with it, by id as well as title, so the widget lands there.
  const askOllie = useCallback(
    (source: AskOllieForWidgetSource, sectionId?: string) => {
      const entryId = uuidv4();
      trackEvent(OpikEvent.DASHBOARD_ASK_OLLIE_CLICKED, {
        entry_id: entryId,
        source,
        dashboard_id: runtimeConfig.dashboardId,
        dashboard_type: runtimeConfig.dashboardType,
        dashboard_scope: runtimeConfig.dashboardScope,
        project_id: runtimeConfig.projectIds?.[0],
      });
      window.opikBridge?.startConversation(
        isTemplateId(runtimeConfig.dashboardId ?? null)
          ? DESCRIBE_TEMPLATE_WIDGET_MESSAGE
          : DESCRIBE_WIDGET_MESSAGE,
        {
          origin: "describe_widget",
          entryId,
          ...(sectionId
            ? { section: getSectionById(sectionId)?.title, sectionId }
            : {}),
        },
      );
    },
    [runtimeConfig, getSectionById],
  );

  return { isOllieAvailable, askOllie };
};

export default useAskOllieForWidget;
