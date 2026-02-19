import React from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import SidebarMenuItem, {
  MenuItem,
} from "@/components/layout/SideBar/MenuItem/SidebarMenuItem";
import useDashboardsList from "@/api/dashboards/useDashboardsList";
import useAlertsList from "@/api/alerts/useAlertsList";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import usePromptsList from "@/api/prompts/usePromptsList";
import useRulesList from "@/api/automations/useRulesList";
import useProjectsList from "@/api/projects/useProjectsList";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { ACTIVE_OPTIMIZATION_FILTER } from "@/lib/optimizations";
import getMenuItems from "@/components/layout/SideBar/helpers/getMenuItems";
import { WithPermissionsProps } from "@/types/permissions";

const RUNNING_OPTIMIZATION_REFETCH_INTERVAL = 5000;

export interface SideBarMenuItemsProps {
  expanded: boolean;
}

const SideBarMenuItems: React.FC<
  SideBarMenuItemsProps & WithPermissionsProps
> = ({ expanded, canViewExperiments }) => {
  const { activeWorkspaceName: workspaceName } = useAppStore();

  const menuItems = getMenuItems({ canViewExperiments });

  const { data: projectData } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: datasetsData } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: experimentsData } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: promptsData } = usePromptsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: rulesData } = useRulesList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: optimizationsData } = useOptimizationsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: runningOptimizationsData } = useOptimizationsList(
    {
      workspaceName,
      filters: ACTIVE_OPTIMIZATION_FILTER,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: RUNNING_OPTIMIZATION_REFETCH_INTERVAL,
      enabled: !!workspaceName,
    },
  );

  const { data: annotationQueuesData } = useAnnotationQueuesList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: dashboardsData } = useDashboardsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const { data: alertsData } = useAlertsList(
    {
      workspaceName,
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
      enabled: expanded,
    },
  );

  const countDataMap: Record<string, number | undefined> = {
    projects: projectData?.total,
    datasets: datasetsData?.total,
    experiments: experimentsData?.total,
    prompts: promptsData?.total,
    rules: rulesData?.total,
    optimizations: optimizationsData?.total,
    annotation_queues: annotationQueuesData?.total,
    alerts: alertsData?.total,
    dashboards: dashboardsData?.total,
  };

  const hasActiveOptimizations = (runningOptimizationsData?.total ?? 0) > 0;

  const indicatorDataMap: Record<string, boolean> = {
    optimizations_running: hasActiveOptimizations,
  };

  const renderItems = (items: MenuItem[]) => {
    return items.map((item) => (
      <SidebarMenuItem
        key={item.id}
        item={item}
        expanded={expanded}
        count={countDataMap[item.count!]}
        hasIndicator={indicatorDataMap[item.showIndicator!]}
      />
    ));
  };

  return (
    <>
      {menuItems.map((menuGroup) => {
        return (
          <li key={menuGroup.id} className={cn(expanded && "mb-1")}>
            <div>
              {menuGroup.label && expanded && (
                <div className="comet-body-s truncate pb-1 pl-2.5 pr-3 pt-3 text-light-slate">
                  {menuGroup.label}
                </div>
              )}

              <ul>
                {renderItems(
                  menuGroup.items.filter(
                    (item): item is MenuItem => item !== null,
                  ),
                )}
              </ul>
            </div>
          </li>
        );
      })}
    </>
  );
};

export default SideBarMenuItems;
