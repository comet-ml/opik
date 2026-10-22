import React, { useCallback, useEffect, useMemo } from "react";
import { AxiosError } from "axios";
import get from "lodash/get";
import uniqid from "uniqid";
import { Pencil } from "lucide-react";
import useInsightsViewsList from "@/api/insights-views/useInsightsViewsList";
import useInsightsViewCreateMutation from "@/api/insights-views/useInsightsViewCreateMutation";
import {
  useDashboardStore,
  selectSetOnAddWidgetToTemplate,
} from "@/store/DashboardStore";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { PROJECT_TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { regenerateAllIds } from "@/lib/dashboard/utils";
import { calculateLayoutForAddingWidget } from "@/lib/dashboard/layout";
import { generateDashboardTypeFilter } from "@/lib/filters";
import {
  DASHBOARD_TYPE,
  DashboardState,
  DashboardWidget,
} from "@/types/dashboard";
import { useToast } from "@/ui/use-toast";
import { ToastAction } from "@/ui/toast";

const getCopyName = (templateName: string, takenNames: Set<string>) => {
  let name = `${templateName} (custom)`;
  for (let n = 2; takenNames.has(name); n++) {
    name = `${templateName} (custom ${n})`;
  }
  return name;
};

// The copy is created with the new widget already in it, so a failed create leaves nothing behind.
const buildCopyConfig = (
  templateConfig: DashboardState,
  sectionId: string,
  widget: DashboardWidget,
): DashboardState => {
  const targetIndex = Math.max(
    templateConfig.sections.findIndex((section) => section.id === sectionId),
    0,
  );
  const config = regenerateAllIds(templateConfig);
  const newWidget = { ...widget, id: uniqid() };

  return {
    ...config,
    sections: config.sections.map((section, index) =>
      index === targetIndex
        ? {
            ...section,
            widgets: [...section.widgets, newWidget],
            layout: calculateLayoutForAddingWidget(section.layout, newWidget),
          }
        : section,
    ),
  };
};

type UseCustomizeTemplateParams = {
  projectId: string;
  dashboardId: string | null;
  enabled: boolean;
  onCopyCreated: (dashboardId: string) => void;
  onRename: () => void;
};

const useCustomizeTemplate = ({
  projectId,
  dashboardId,
  enabled,
  onCopyCreated,
  onRename,
}: UseCustomizeTemplateParams) => {
  const workspaceName = useActiveWorkspaceName();
  const { toast } = useToast();
  const setOnAddWidgetToTemplate = useDashboardStore(
    selectSetOnAddWidgetToTemplate,
  );

  const template = PROJECT_TEMPLATE_LIST.find((t) => t.id === dashboardId);
  const isActive = enabled && Boolean(template);

  const filters = useMemo(
    () => generateDashboardTypeFilter(DASHBOARD_TYPE.MULTI_PROJECT),
    [],
  );
  const { data: viewsData } = useInsightsViewsList(
    { workspaceName, projectId, filters, page: 1, size: 1000 },
    { enabled: isActive },
  );

  const { mutate: createView } = useInsightsViewCreateMutation({
    skipDefaultError: true,
  });

  const addWidgetToTemplate = useCallback(
    (sectionId: string, widget: DashboardWidget) => {
      if (!template) return;

      const name = getCopyName(
        template.name,
        new Set((viewsData?.content ?? []).map((view) => view.name)),
      );

      createView(
        {
          dashboard: {
            name,
            description: template.description,
            config: buildCopyConfig(template.config, sectionId, widget),
            type: DASHBOARD_TYPE.MULTI_PROJECT,
            project_id: projectId,
          },
        },
        {
          onSuccess: (data) => {
            if (!data?.id) return;
            onCopyCreated(data.id);
            toast({
              title: `Saved as "${name}"`,
              description:
                "Built-in views are read-only, so your changes go to this copy.",
              actions: [
                <ToastAction
                  key="rename"
                  variant="link"
                  size="sm"
                  className="px-0"
                  altText="Rename"
                  onClick={onRename}
                >
                  <Pencil className="mr-1.5 size-3.5" />
                  Rename
                </ToastAction>,
              ],
            });
          },
          onError: (error: AxiosError) => {
            toast({
              title: "Could not add the widget",
              description: get(
                error,
                ["response", "data", "message"],
                error.message,
              ),
              variant: "destructive",
            });
          },
        },
      );
    },
    [
      template,
      viewsData?.content,
      createView,
      projectId,
      onCopyCreated,
      onRename,
      toast,
    ],
  );

  useEffect(() => {
    if (!isActive) return;
    setOnAddWidgetToTemplate(addWidgetToTemplate);
    return () => setOnAddWidgetToTemplate(null);
  }, [isActive, addWidgetToTemplate, setOnAddWidgetToTemplate]);
};

export default useCustomizeTemplate;
